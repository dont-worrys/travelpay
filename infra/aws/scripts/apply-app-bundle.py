#!/usr/bin/env python3
"""확인된 설정 묶음으로 앱 호스트의 Alloy 로그 수집기를 설치하고 바뀐 경우에만 다시 시작합니다. fetch-config-bundle.py가 실행합니다."""

import hashlib
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request


class ApplyError(Exception):
    pass


UNIT = "travelpay-alloy.service"
READY_URL = "http://127.0.0.1:12345/-/ready"


class Paths:
    def __init__(self, root=Path("/")):
        self.config = root / "etc/travelpay/alloy/config.alloy"
        self.unit = root / "etc/systemd/system" / UNIT
        self.storage = root / "var/lib/travelpay/alloy"
        # 마지막으로 준비 확인까지 성공한 설정의 해시입니다.
        self.applied = root / "var/lib/travelpay/alloy-applied.sha256"


def failure_message(error):
    reason = str(error) if isinstance(error, ApplyError) else type(error).__name__
    return f"App bundle apply failed: {reason}"


def install(source, target, mode):
    """내용이 같으면 그대로 두고, 다르면 임시 파일로 쓴 뒤 교체해 읽는 쪽이 반쯤 쓴 파일을 보지 않게 합니다."""
    contents = source.read_bytes()
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.read_bytes() == contents:
        target.chmod(mode)
        return
    temporary = target.with_name(target.name + ".new")
    temporary.write_bytes(contents)
    temporary.chmod(mode)
    os.replace(temporary, target)


def wait_until_ready(url, opener=urllib.request.urlopen, clock=time.monotonic, sleep=time.sleep, timeout=300):
    # 첫 시작은 이미지를 내려받으므로 시작 명령이 끝난 뒤에도 수집기가 한동안 응답하지 않습니다.
    deadline = clock() + timeout
    while True:
        try:
            with opener(url, timeout=5) as response:
                if response.status == 200:
                    return
        except OSError:
            pass
        if clock() >= deadline:
            raise ApplyError("제한 시간 안에 Alloy가 준비되지 않았습니다.")
        sleep(5)


def apply(source, run=subprocess.run, paths=None, wait=wait_until_ready):
    paths = paths or Paths()
    install(source / "config.alloy", paths.config, 0o644)
    install(source / UNIT, paths.unit, 0o644)
    paths.storage.mkdir(parents=True, exist_ok=True)
    paths.storage.chmod(0o700)
    wanted = hashlib.sha256(paths.config.read_bytes() + b"\0" + paths.unit.read_bytes()).hexdigest()
    applied = paths.applied.read_text() if paths.applied.exists() else None
    run(["systemctl", "daemon-reload"], check=True, timeout=60)
    run(["systemctl", "enable", UNIT], check=True, timeout=60)
    # 이전 실패의 자동 재시작이 시작 횟수 제한을 다 썼어도 이번 적용은 시작할 수 있게 합니다.
    run(["systemctl", "reset-failed", UNIT], check=True, timeout=60)
    # 마지막으로 성공한 적용과 설정이 다르면 컨테이너를 다시 만들고, 같으면 멈춰 있을 때만 시작합니다. 설치 뒤 다시 시작하기 전에
    # 실패한 적용을 재시도해도 기록이 이전 값이므로 다시 만듭니다. 컨테이너는 bind한 설정 파일의 이전 내용을 계속 읽기 때문입니다.
    run(["systemctl", "restart" if wanted != applied else "start", UNIT], check=True, timeout=600)
    wait(READY_URL)
    paths.applied.write_text(wanted)


if __name__ == "__main__":
    try:
        apply(Path(sys.argv[1]))
    except Exception as error:
        print(failure_message(error), file=sys.stderr)
        sys.exit(1)
