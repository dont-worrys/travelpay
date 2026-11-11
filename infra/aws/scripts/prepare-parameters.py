#!/usr/bin/env python3
"""검토한 plan의 앱 비밀값을 SSM에 준비합니다. 기존 값은 덮어쓰지 않습니다.

DB·Redis 암호는 새로 만듭니다. Wallet 계좌번호 키는 --create-wallet-key를 줄 때만 만들고,
외부에서 발급받은 키는 --external을 줄 때만 화면에 표시하지 않고 입력받습니다.
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
REQUIRED_NAMES = {"REDIS_SESSION_PASSWORD", "REDIS_CACHE_PASSWORD", WALLET_KEY_NAME} | EXTERNAL_NAMES
DATABASE_PASSWORD_COUNT = 6


class ParameterPreparationError(ValueError):
    pass


def is_generated(name):
    return name.endswith("_DB_PASSWORD") or name in ("REDIS_SESSION_PASSWORD", "REDIS_CACHE_PASSWORD", WALLET_KEY_NAME)


def generate(name):
    if name.endswith("/" + WALLET_KEY_NAME):
        return base64.b64encode(secrets.token_bytes(32)).decode()
    return secrets.token_hex(32)


def read_external(name, prompt=getpass.getpass):
    with warnings.catch_warnings():
        # getpass는 입력을 숨길 수 없으면(터미널이 아닌 입력) 경고만 내고 입력을 그대로 받습니다. 그 경우 받지 않습니다.
        warnings.simplefilter("error", getpass.GetPassWarning)
        try:
            value = prompt(f"{name.rsplit('/', 1)[-1]} (입력 내용은 표시되지 않습니다): ")
        except getpass.GetPassWarning:
            raise ParameterPreparationError("입력을 숨길 수 있는 터미널에서 직접 실행하세요") from None
    # 쿼리 인자로 보내는 키입니다. 공백·제어 문자가 섞이면 복사 실수로 보고 저장하지 않습니다.
    if not re.fullmatch(r"[!-~]{8,512}", value):
        raise ParameterPreparationError("외부 키는 공백 없는 8~512자의 ASCII여야 합니다")
    return value


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
        raise ParameterPreparationError("DB 6개·Redis 2개·Wallet 키·외부 키의 계약을 확인하세요.")
    # 외부 키 입력을 잘못해 중단돼도 만들 수 있는 값은 먼저 준비되도록 외부 키를 마지막에 둡니다.
    return sorted(names, key=lambda name: (name.rsplit("/", 1)[-1] in EXTERNAL_NAMES, name))


def history_enabled(run_text):
    # AWS CLI 기록(cli_history)을 켜면 요청 내용이 로컬 기록 DB에 남습니다. 비밀값을 쓰기 전에 확인합니다.
    return run_text(["configure", "get", "cli_history"]).strip().lower() == "enabled"


def failure_message(error):
    reason = str(error) if isinstance(error, ParameterPreparationError) else type(error).__name__
    return f"Parameter preparation failed: {reason}. 기존 값을 덮어쓰지 않습니다."


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

    prepare(aws, names, args.apply, read_external if args.external else None, args.create_wallet_key,
            report=lambda name, status: print(f"{name}: {status}", flush=True))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(failure_message(error), file=sys.stderr)
        sys.exit(1)
