#!/usr/bin/env python3
"""검토한 plan의 DB·Redis 암호만 SSM에 준비합니다. 기존 값은 덮어쓰지 않습니다."""

import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys


class ParameterPreparationError(ValueError):
    pass


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


def prepare(aws, names, apply=False):
    statuses = []
    for name in names:
        existing = aws("describe-parameters", {"ParameterFilters": [{"Key": "Name", "Option": "Equals", "Values": [name]}]})["Parameters"]
        if existing:
            if len(existing) != 1 or existing[0]["Type"] != "SecureString":
                raise ParameterPreparationError("기존 항목이 SecureString 하나가 아니므로 변경하지 않습니다.")
            statuses.append((name, "preserved"))
        elif apply:
            aws("put-parameter", {"Name": name, "Value": secrets.token_hex(32), "Type": "SecureString",
                                  "Tier": "Standard", "Overwrite": False})
            statuses.append((name, "created"))
        else:
            statuses.append((name, "would-create"))
    return statuses


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plan", type=Path)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--account", required=True)
    parser.add_argument("--apply", action="store_true", help="생성 승인 후에만 사용합니다. 기본은 읽기 전용 미리보기입니다.")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]{12}", args.account):
        raise ParameterPreparationError("대상 계정 ID를 확인하세요.")
    env = dict(os.environ, AWS_PROFILE=args.profile, AWS_RETRY_MODE="standard", AWS_MAX_ATTEMPTS="5")

    def run(command, payload=None):
        return run_json(command, payload, env)

    base = ["aws", "--profile", args.profile, "--region", "ap-northeast-2", "--no-cli-pager", "--output", "json"]
    identity = run(base + ["sts", "get-caller-identity"])
    if identity["Account"] != args.account:
        raise ParameterPreparationError("로그인 계정이 대상 계정과 다릅니다.")
    root = Path(__file__).resolve().parents[1]
    plan = run(["terraform", f"-chdir={root}", "show", "-json", str(args.plan.resolve())])
    contract = plan["planned_values"]["outputs"]["runtime_secret_parameters"]["value"]
    names = []
    for name, arn in contract.items():
        if name.endswith("_DB_PASSWORD") or name in ("REDIS_SESSION_PASSWORD", "REDIS_CACHE_PASSWORD"):
            prefix = f"arn:aws:ssm:ap-northeast-2:{args.account}:parameter/travelpay/production/"
            if arn != prefix + name or not re.fullmatch(r"[A-Z_]+", name):
                raise ParameterPreparationError("plan의 Parameter ARN이 대상 계정·경로와 다릅니다.")
            names.append("/travelpay/production/" + name)
    if len(names) != 8:
        raise ParameterPreparationError("DB 6개·Redis 2개의 암호 계약을 확인하세요.")

    def aws(operation, payload):
        return run(base + ["ssm", operation], payload)

    for name, status in prepare(aws, sorted(names), args.apply):
        print(f"{name}: {status}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(failure_message(error), file=sys.stderr)
        sys.exit(1)
