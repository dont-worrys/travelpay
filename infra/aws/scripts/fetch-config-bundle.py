#!/usr/bin/env python3
"""SSM association이 Data·앱 호스트에서 실행합니다. S3 설정 묶음의 SHA-256을 확인한 뒤 지정한 묶음 적용 스크립트를 실행합니다."""

import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time


class BundleError(Exception):
    pass


# IAM 변경은 전파에 시간이 걸립니다. 묶음 읽기 권한과 association을 같은 apply에서 만들면 첫 실행의 읽기가 거절될 수 있어
# 거절(AccessDenied)만 정한 횟수까지 다시 시도합니다. 다른 오류는 바로 실패합니다.
DOWNLOAD_ATTEMPTS = 6
DOWNLOAD_RETRY_SECONDS = 10


def failure_message(error):
    reason = str(error) if isinstance(error, BundleError) else type(error).__name__
    return f"Config bundle fetch failed: {reason}"


def unpack(compressed, expected_sha256, target, apply_script):
    document = gzip.decompress(compressed)
    if hashlib.sha256(document).hexdigest() != expected_sha256:
        raise BundleError("설정 묶음의 SHA-256이 Terraform이 기록한 값과 다릅니다.")
    files = json.loads(document)
    if apply_script not in files:
        raise BundleError("설정 묶음에 적용 스크립트가 없습니다.")
    for name, contents in files.items():
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name):
            raise BundleError("설정 묶음에 허용되지 않은 파일 이름이 있습니다.")
        path = target / name
        path.write_text(contents)
        path.chmod(0o600)
    return sorted(files)


def read_request(environ):
    names = ("BUNDLE_BUCKET", "BUNDLE_KEY", "BUNDLE_SHA256", "REGION", "APPLY_SCRIPT")
    request = {name: environ.get("TRAVELPAY_" + name, "") for name in names}
    if not re.fullmatch(r"[0-9a-f]{64}", request["BUNDLE_SHA256"]) or not all(request.values()):
        raise BundleError("SSM 매개변수가 비어 있거나 SHA-256 형식이 아닙니다.")
    # 묶음 안의 어떤 파일을 실행할지는 SSM 문서의 허용 패턴과 같은 두 이름으로만 정합니다.
    if not re.fullmatch(r"apply-(data|app)-bundle\.py", request["APPLY_SCRIPT"]):
        raise BundleError("허용되지 않은 적용 스크립트 이름입니다.")
    return request


def main(environ=os.environ, run=subprocess.run, which=shutil.which, sleep=time.sleep):
    request = read_request(environ)
    # AL2023에는 SSM Agent가 미리 있어 user data보다 먼저 실행될 수 있습니다. cloud-init 종료 상태 대신 필요한 도구를 확인합니다.
    run(["cloud-init", "status", "--wait"], capture_output=True, timeout=600)
    missing = [tool for tool in ("aws", "docker", "python3", "curl") if which(tool) is None]
    if missing:
        raise BundleError("필요한 도구가 설치되지 않았습니다: " + ", ".join(missing))
    with tempfile.TemporaryDirectory(prefix="travelpay-bundle-") as work:
        archive = Path(work) / "bundle.json.gz"
        for attempt in range(1, DOWNLOAD_ATTEMPTS + 1):
            result = run(["aws", "s3api", "get-object", "--region", request["REGION"], "--bucket", request["BUNDLE_BUCKET"],
                          "--key", request["BUNDLE_KEY"], str(archive)], capture_output=True, text=True, timeout=120)
            if not result.returncode:
                break
            # 권한·객체 없음·네트워크 문제를 구분할 수 있게 AWS 오류 코드만 남깁니다.
            found = re.search(r"\(([A-Za-z0-9]+)\)", result.stderr or "")
            code = found.group(1) if found else "오류 코드 없음"
            if code != "AccessDenied" or attempt == DOWNLOAD_ATTEMPTS:
                raise BundleError("S3 묶음을 받지 못했습니다: " + code)
            sleep(DOWNLOAD_RETRY_SECONDS)
        staging = Path(work) / "files"
        staging.mkdir(mode=0o700)
        unpack(archive.read_bytes(), request["BUNDLE_SHA256"], staging, request["APPLY_SCRIPT"])
        run([sys.executable, str(staging / request["APPLY_SCRIPT"]), str(staging)], check=True, timeout=1200)
    print("Config bundle applied: " + request["BUNDLE_SHA256"])


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # AWS 오류 원문에는 요청 정보가 들어갈 수 있어 실패 종류만 남깁니다. 적용 스크립트 출력은 그대로 SSM에 남습니다.
        print(failure_message(error), file=sys.stderr)
        sys.exit(1)
