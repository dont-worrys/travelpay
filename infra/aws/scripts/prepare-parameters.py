#!/usr/bin/env python3
"""검토한 plan의 앱 비밀값을 SSM에 준비합니다. 기존 값은 덮어쓰지 않습니다.

DB·Redis·Grafana 관리자 암호와 원본 인증 값은 새로 만듭니다. Wallet 계좌번호 키는 --create-wallet-key를 줄 때만 만들고,
외부에서 발급받은 키는 --external을 줄 때만 화면에 표시하지 않고 입력받습니다. 기존 값을 덮어쓰는 경우는 두 가지뿐입니다.
Grafana 관리자 암호는 --set-grafana-password를 줄 때 운영자가 정한 값으로, DB 앱 계정 암호 6개는
--rotate-database-passwords를 줄 때 새 임의 값으로 바꿉니다.
"""

import argparse
import base64
import getpass
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import warnings

PARAMETER_PREFIX = "/travelpay/production/"
# 수출입은행에서 발급받는 키입니다. 만들어 낼 수 없으므로 운영자가 입력합니다.
EXTERNAL_NAMES = {"KOREAEXIM_API_KEY"}
# Wallet은 이 값을 Base64로 풀어 32바이트 AES 키로 씁니다. 데이터가 생긴 뒤 바꾸거나 지워진 뒤 새로 만들면
# 저장된 계좌번호를 풀 수 없으므로, 처음 한 번만 명시적으로 만듭니다.
WALLET_KEY_NAME = "WALLET_ACCOUNT_NUMBER_KEY"
# Data 호스트의 Grafana 관리자 암호입니다(monitoring.tf).
GRAFANA_PASSWORD_NAME = "GRAFANA_ADMIN_PASSWORD"
MONITORING_NAMES = {GRAFANA_PASSWORD_NAME}
# Cloudflare Transform Rule이 붙이고 ALB 규칙이 확인하는 헤더 값입니다(public-ingress.tf). ALB 헤더 조건은 대소문자를 구분하지 않고
# *·?를 와일드카드로 보며 128자까지라, 생성 값(소문자 16진수 64자)을 그대로 씁니다.
ORIGIN_AUTH_NAME = "ORIGIN_AUTH"
# 브라우저에 입력하는 암호라 앞뒤 공백 없이 출력 가능한 ASCII 10~128자(공백 포함)만 받습니다. 한글은 암호 칸에서 입력기가
# 꺼지는 환경이 있어 받지 않습니다. Data 호스트의 prepare-monitoring.py와 같은 규칙입니다.
GRAFANA_PASSWORD_PATTERN = r"[!-~][ -~]{8,126}[!-~]"
REQUIRED_NAMES = ({"REDIS_SESSION_PASSWORD", "REDIS_CACHE_PASSWORD", WALLET_KEY_NAME, ORIGIN_AUTH_NAME} | MONITORING_NAMES
                  | EXTERNAL_NAMES)
DATABASE_PASSWORD_COUNT = 6


class ParameterPreparationError(ValueError):
    pass


def is_generated(name):
    return (name.endswith("_DB_PASSWORD") or name in ("REDIS_SESSION_PASSWORD", "REDIS_CACHE_PASSWORD", WALLET_KEY_NAME)
            or name in MONITORING_NAMES or name == ORIGIN_AUTH_NAME)


def generate(name):
    if name.endswith("/" + WALLET_KEY_NAME):
        return base64.b64encode(secrets.token_bytes(32)).decode()
    return secrets.token_hex(32)


def ask_hidden(prompt, message):
    with warnings.catch_warnings():
        # getpass는 입력을 숨길 수 없으면(터미널이 아닌 입력) 경고만 내고 입력을 그대로 받습니다. 그 경우 받지 않습니다.
        warnings.simplefilter("error", getpass.GetPassWarning)
        try:
            return prompt(message)
        except getpass.GetPassWarning:
            raise ParameterPreparationError("입력을 숨길 수 있는 터미널에서 직접 실행하세요") from None


def read_external(name, prompt=getpass.getpass):
    value = ask_hidden(prompt, f"{name.rsplit('/', 1)[-1]} (입력 내용은 표시되지 않습니다): ")
    # 쿼리 인자로 보내는 키입니다. 공백·제어 문자가 섞이면 복사 실수로 보고 저장하지 않습니다.
    if not re.fullmatch(r"[!-~]{8,512}", value):
        raise ParameterPreparationError("외부 키는 공백 없는 8~512자의 ASCII여야 합니다")
    return value


def read_grafana_password(prompt=getpass.getpass):
    value = ask_hidden(prompt, "새 Grafana 관리자 암호 (입력 내용은 표시되지 않습니다): ")
    if ask_hidden(prompt, "같은 암호를 한 번 더 입력하세요: ") != value:
        raise ParameterPreparationError("두 번 입력한 암호가 다릅니다")
    if not re.fullmatch(GRAFANA_PASSWORD_PATTERN, value):
        raise ParameterPreparationError("Grafana 암호는 앞뒤 공백 없이 영문·숫자·기호·공백 10~128자여야 합니다(한글 제외)")
    return value


def set_grafana_password(aws, value):
    aws("put-parameter", {"Name": PARAMETER_PREFIX + GRAFANA_PASSWORD_NAME, "Value": value, "Type": "SecureString",
                          "Tier": "Standard", "Overwrite": True})


def rotate_database_passwords(aws, names, apply=False, report=lambda name, status: None):
    # SSM 값만 바뀌므로 DB에는 초기화 스크립트를 다시 실행해야 반영됩니다. 그 전에 시작하는 Task는 DB 로그인에 실패합니다.
    for name in names:
        if not name.endswith("_DB_PASSWORD"):
            continue
        if not apply:
            report(name, "would-rotate")
            continue
        aws("put-parameter", {"Name": name, "Value": generate(name), "Type": "SecureString", "Tier": "Standard",
                              "Overwrite": True})
        report(name, "rotated")


def parameter_names(contract, account):
    names = []
    for name, arn in contract.items():
        if not re.fullmatch(r"[A-Z_]+", name) or arn != f"arn:aws:ssm:ap-northeast-2:{account}:parameter{PARAMETER_PREFIX}{name}":
            raise ParameterPreparationError("plan의 Parameter ARN이 대상 계정·경로와 다릅니다.")
        if not (is_generated(name) or name in EXTERNAL_NAMES):
            raise ParameterPreparationError(f"준비 방법이 정해지지 않은 비밀값입니다: {name}")
        names.append(PARAMETER_PREFIX + name)
    database_names = [name for name in contract if name.endswith("_DB_PASSWORD")]
    if len(database_names) != DATABASE_PASSWORD_COUNT or not REQUIRED_NAMES <= set(contract):
        raise ParameterPreparationError("DB 6개·Redis 2개·Grafana·Wallet 키·원본 인증 값·외부 키의 계약을 확인하세요.")
    # 외부 키 입력을 잘못해 중단돼도 만들 수 있는 값은 먼저 준비되도록 외부 키를 마지막에 둡니다.
    return sorted(names, key=lambda name: (name.rsplit("/", 1)[-1] in EXTERNAL_NAMES, name))


def history_enabled(run_text):
    # AWS CLI 기록(cli_history)을 켜면 요청 내용이 로컬 기록 DB에 남습니다. 비밀값을 쓰기 전에 확인합니다.
    return run_text(["configure", "get", "cli_history"]).strip().lower() == "enabled"


def failure_message(error):
    reason = str(error) if isinstance(error, ParameterPreparationError) else type(error).__name__
    return (f"Parameter preparation failed: {reason}. 위에 created·rotated·updated로 출력된 항목은 바뀌었고, "
            "실패한 항목은 바뀌었을 수 있습니다.")


def run_json(command, payload=None, env=None):
    descriptor = None
    try:
        if payload is not None:
            # Linux의 익명 메모리 파일입니다. 디스크 파일·명령 인자에 비밀값을 남기지 않습니다.
            # 현재 AWS CLI는 /dev/stdin JSON을 읽지 못하므로 열린 메모리 파일 경로를 전달합니다.
            descriptor = os.memfd_create("travelpay-ssm-request", os.MFD_CLOEXEC)
            os.write(descriptor, json.dumps(payload).encode())
            os.lseek(descriptor, 0, os.SEEK_SET)
            command = command + ["--cli-input-json", f"file:///proc/{os.getpid()}/fd/{descriptor}"]
        result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=60, check=True)
        return json.loads(result.stdout)
    finally:
        if descriptor is not None:
            os.close(descriptor)


def prepare(aws, names, apply=False, external_value=None, create_wallet_key=False, report=lambda name, status: None):
    statuses = []

    def record(name, status):
        # 중간에 실패해도 이미 만든 항목을 알 수 있도록 결정마다 바로 알립니다.
        statuses.append((name, status))
        report(name, status)

    for name in names:
        existing = aws("describe-parameters", {"ParameterFilters": [{"Key": "Name", "Option": "Equals", "Values": [name]}]})["Parameters"]
        if existing:
            if len(existing) != 1 or existing[0]["Type"] != "SecureString":
                raise ParameterPreparationError("기존 항목이 SecureString 하나가 아니므로 변경하지 않습니다.")
            record(name, "preserved")
            continue
        short_name = name.rsplit("/", 1)[-1]
        external = short_name in EXTERNAL_NAMES
        if external and external_value is None:
            record(name, "needs-external-input")
            continue
        if short_name == WALLET_KEY_NAME and not create_wallet_key:
            record(name, "needs-create-wallet-key")
            continue
        if not apply:
            record(name, "would-create")
            continue
        value = external_value(name) if external else generate(name)
        aws("put-parameter", {"Name": name, "Value": value, "Type": "SecureString", "Tier": "Standard", "Overwrite": False})
        record(name, "created")
    return statuses


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plan", type=Path)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--account", required=True)
    parser.add_argument("--apply", action="store_true", help="생성 승인 후에만 사용합니다. 기본은 읽기 전용 미리보기입니다.")
    parser.add_argument("--external", action="store_true", help="--apply와 함께 주면 없는 외부 발급 키를 화면에 표시하지 않고 입력받습니다.")
    parser.add_argument("--create-wallet-key", action="store_true",
                        help="--apply와 함께 주면 Wallet 계좌번호 키를 만듭니다. Wallet 데이터가 생기기 전 처음 한 번만 씁니다.")
    parser.add_argument("--set-grafana-password", action="store_true",
                        help="--apply와 함께 주면 Grafana 관리자 암호를 화면에 표시하지 않고 두 번 입력받아 바꿉니다. "
                             "실행 중인 Grafana에 반영하는 절차는 가이드를 따릅니다.")
    parser.add_argument("--rotate-database-passwords", action="store_true",
                        help="--apply와 함께 주면 DB 앱 계정 암호 6개를 새 임의 값으로 바꿉니다. DB에는 초기화 스크립트를 다시 "
                             "실행해야 반영되므로 가이드의 DB 작업 창 안에서만 씁니다.")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]{12}", args.account):
        raise ParameterPreparationError("대상 계정 ID를 확인하세요.")
    env = dict(os.environ, AWS_PROFILE=args.profile, AWS_RETRY_MODE="standard", AWS_MAX_ATTEMPTS="5")

    def run(command, payload=None):
        return run_json(command, payload, env)

    def run_text(arguments):
        return subprocess.run(["aws", *arguments, "--profile", args.profile], env=env, capture_output=True, text=True,
                              timeout=60).stdout

    base = ["aws", "--profile", args.profile, "--region", "ap-northeast-2", "--no-cli-pager", "--output", "json"]
    identity = run(base + ["sts", "get-caller-identity"])
    if identity["Account"] != args.account:
        raise ParameterPreparationError("로그인 계정이 대상 계정과 다릅니다.")
    if args.apply and history_enabled(run_text):
        raise ParameterPreparationError("AWS CLI 기록(cli_history)이 켜져 있어 비밀값이 로컬 기록에 남습니다. 끈 뒤 실행하세요")
    root = Path(__file__).resolve().parents[1]
    plan = run(["terraform", f"-chdir={root}", "show", "-json", str(args.plan.resolve())])
    contract = plan["planned_values"]["outputs"]["runtime_secret_parameters"]["value"]
    names = parameter_names(contract, args.account)

    def aws(operation, payload):
        return run(base + ["ssm", operation], payload)

    def report(name, status):
        print(f"{name}: {status}", flush=True)

    prepare(aws, names, args.apply, read_external if args.external else None, args.create_wallet_key, report=report)
    if args.rotate_database_passwords:
        rotate_database_passwords(aws, names, args.apply, report=report)
    if args.set_grafana_password:
        if args.apply:
            set_grafana_password(aws, read_grafana_password())
        print(f"{PARAMETER_PREFIX}{GRAFANA_PASSWORD_NAME}: {'updated' if args.apply else 'would-update'}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(failure_message(error), file=sys.stderr)
        sys.exit(1)
