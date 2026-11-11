#!/usr/bin/env python3
"""EC2에서 지정된 EBS만 확인하여 마운트합니다. 기존 파일시스템은 포맷하지 않습니다."""

import json
from pathlib import Path
import re
import subprocess
import sys
import time


class StorageError(Exception):
    pass


def failure_message(error):
    reason = str(error) if isinstance(error, StorageError) else type(error).__name__
    return f"Data storage preparation failed: {reason}"


def select_device(devices, volume):
    matches = [d for d in devices if (d.get("serial") or "").replace("-", "") == volume["id"].replace("-", "")]
    if len(matches) != 1:
        raise StorageError("EBS 식별자가 일치하는 디스크를 하나로 확정할 수 없습니다.")
    disk = matches[0]
    mounts = [m for m in disk.get("mountpoints", []) if m]
    if (disk["type"] != "disk" or disk.get("children") or int(disk["size"]) != volume["size_bytes"]
            or disk.get("fstype") not in (None, "", "ext4") or any(m != volume["mount"] for m in mounts)):
        raise StorageError("EBS 크기·파티션·파일시스템·마운트 경로가 예상과 다릅니다.")
    return disk


def needs_format(disk, signatures):
    if disk.get("fstype") == "ext4":
        return False
    if signatures:
        raise StorageError("알 수 없는 디스크 서명이 있으므로 포맷하지 않습니다.")
    return True


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE, timeout=120).strip()


def mount_volume(volume):
    # EBS 부착과 cloud-init은 병렬 진행될 수 있으므로 식별자가 보일 때까지 제한 시간만 기다립니다.
    deadline = time.monotonic() + 300
    while True:
        devices = json.loads(run("lsblk", "--json", "--bytes", "--output", "PATH,SERIAL,SIZE,TYPE,FSTYPE,MOUNTPOINTS"))["blockdevices"]
        present = any((d.get("serial") or "").replace("-", "") == volume["id"].replace("-", "") for d in devices)
        if present:
            break
        if time.monotonic() >= deadline:
            raise StorageError("EBS 부착 대기 시간이 초과됐습니다.")
        time.sleep(5)
    disk = select_device(devices, volume)
    signatures = json.loads(run("wipefs", "--json", disk["path"])).get("signatures") or []
    if needs_format(disk, signatures):
        run("mkfs.ext4", "-q", disk["path"])
    uuid = run("blkid", "-s", "UUID", "-o", "value", disk["path"])
    if not re.fullmatch(r"[a-fA-F0-9-]{36}", uuid):
        raise StorageError("파일시스템 UUID를 확인할 수 없습니다.")
    target = Path(volume["mount"])
    target.mkdir(parents=True, exist_ok=True)
    fstab = Path("/etc/fstab")
    contents = fstab.read_text()
    existing = [line.split() for line in contents.splitlines() if line.strip() and not line.lstrip().startswith("#")]
    entries = [entry for entry in existing if len(entry) > 1 and entry[1] == str(target)]
    if entries and (len(entries) != 1 or entries[0][0] != f"UUID={uuid}"):
        raise StorageError("기존 fstab 경로가 다른 디스크를 가리킵니다.")
    if not entries:
        # nofail을 쓰지 않습니다. 데이터 디스크 없는 빈 디렉터리에 서비스를 시작하면 안 됩니다.
        fstab.write_text(contents.rstrip() + f"\nUUID={uuid} {target} ext4 defaults 0 2\n")
    if target.is_mount():
        if run("findmnt", "-n", "-o", "UUID", "--mountpoint", str(target)) != uuid:
            raise StorageError("마운트된 디스크의 UUID가 예상과 다릅니다.")
    else:
        run("mount", str(target))


def main():
    config = json.loads((Path(__file__).resolve().parent / "runtime.json").read_text())
    for volume in config["volumes"].values():
        mount_volume(volume)


if __name__ == "__main__":
    try:
        main()
    except (StorageError, OSError, ValueError, subprocess.SubprocessError) as error:
        print(failure_message(error), file=sys.stderr)
        sys.exit(1)
