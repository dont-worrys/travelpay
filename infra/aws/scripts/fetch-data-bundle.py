#!/usr/bin/env python3
"""SSM association이 Data 호스트에서 실행합니다. S3 설정 묶음의 SHA-256을 확인한 뒤 묶음의 적용 스크립트를 실행합니다."""

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


class BundleError(Exception):
    pass


def failure_message(error):
    reason = str(error) if isinstance(error, BundleError) else type(error).__name__
    return f"Data bundle fetch failed: {reason}"


def unpack(compressed, expected_sha256, target):
    document = gzip.decompress(compressed)
    if hashlib.sha256(document).hexdigest() != expected_sha256:
        raise BundleError("설정 묶음의 SHA-256이 Terraform이 기록한 값과 다릅니다.")
    files = json.loads(document)
    if "apply-data-bundle.py" not in files:
        raise BundleError("설정 묶음에 적용 스크립트가 없습니다.")
    for name, contents in files.items():
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name):
            raise BundleError("설정 묶음에 허용되지 않은 파일 이름이 있습니다.")
        path = target / name
        path.write_text(contents)
        path.chmod(0o600)
    return sorted(files)


def read_request(environ):
    request = {name: environ.get("TRAVELPAY_" + name, "") for name in ("BUNDLE_BUCKET", "BUNDLE_KEY", "BUNDLE_SHA256", "REGION")}
    if not re.fullmatch(r"[0-9a-f]{64}", request["BUNDLE_SHA256"]) or not all(request.values()):
        raise BundleError("SSM 매개변수가 비어 있거나 SHA-256 형식이 아닙니다.")
    return request


def main(environ=os.environ, run=subprocess.run, which=shutil.which):
    request = read_request(environ)
    # AL2023에는 SSM Agent가 미리 있어 user data보다 먼저 실행될 수 있습니다. cloud-init 종료 상태 대신 필요한 도구를 확인합니다.
    run(["cloud-init", "status", "--wait"], capture_output=True, timeout=600)
    missing = [tool for tool in ("aws", "docker", "python3", "curl") if which(tool) is None]
    if missing:
        raise BundleError("필요한 도구가 설치되지 않았습니다: " + ", ".join(missing))
    with tempfile.TemporaryDirectory(prefix="travelpay-bundle-") as work:
        archive = Path(work) / "bundle.json.gz"
        result = run(["aws", "s3api", "get-object", "--region", request["REGION"], "--bucket", request["BUNDLE_BUCKET"],
                      "--key", request["BUNDLE_KEY"], str(archive)], capture_output=True, text=True, timeout=120)
        if result.returncode:
            # 권한·객체 없음·네트워크 문제를 구분할 수 있게 AWS 오류 코드만 남깁니다.
            code = re.search(r"\(([A-Za-z0-9]+)\)", result.stderr or "")
            raise BundleError("S3 묶음을 받지 못했습니다: " + (code.group(1) if code else "오류 코드 없음"))
        staging = Path(work) / "files"
        staging.mkdir(mode=0o700)
        unpack(archive.read_bytes(), request["BUNDLE_SHA256"], staging)
        run([sys.executable, str(staging / "apply-data-bundle.py"), str(staging)], check=True, timeout=1200)
    print("Data bundle applied: " + request["BUNDLE_SHA256"])


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # AWS 오류 원문에는 요청 정보가 들어갈 수 있어 실패 종류만 남깁니다. 적용 스크립트 출력은 그대로 SSM에 남습니다.
        print(failure_message(error), file=sys.stderr)
        sys.exit(1)
