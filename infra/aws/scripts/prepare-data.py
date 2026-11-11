#!/usr/bin/env python3
"""Data EC2의 임시 Redis 설정과 영속 Kafka ID를 준비합니다. 비밀값은 출력하지 않습니다."""

import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid


class PreparationError(Exception):
    pass


def failure_message(error):
    reason = str(error) if isinstance(error, PreparationError) else type(error).__name__
    return f"Data runtime preparation failed: {reason}"


def redis_config(password):
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", password):
        raise PreparationError("Redis 비밀번호는 32~128자의 영문·숫자·밑줄·하이픈이어야 합니다.")
    return ("bind 0.0.0.0\nprotected-mode yes\nmaxmemory 128mb\nmaxmemory-policy noeviction\n"
            'save ""\nappendonly no\n' + f"requirepass {password}\n")


def cluster_id(root):
    saved = root / "cluster.id"
    metadata = root / "data" / "meta.properties"
    recorded = None
    if metadata.exists():
        entries = [line.split("=", 1)[1].strip() for line in metadata.read_text().splitlines() if line.startswith("cluster.id=")]
        if len(entries) != 1:
            raise PreparationError("기존 Kafka 메타데이터에서 cluster ID를 확인할 수 없습니다.")
        recorded = entries[0]
    current = saved.read_text().strip() if saved.exists() else recorded
    if recorded and current != recorded:
        raise PreparationError("저장된 Kafka cluster ID와 데이터의 ID가 다릅니다.")
    if current is None:
        if (root / "data").exists() and any((root / "data").iterdir()):
            raise PreparationError("ID를 확인할 수 없는 Kafka 데이터는 초기화하지 않습니다.")
        current = base64.urlsafe_b64encode(uuid.uuid4().bytes).decode().rstrip("=")
    if not re.fullmatch(r"[A-Za-z0-9_-]{22}", current):
        raise PreparationError("Kafka cluster ID 형식이 올바르지 않습니다.")
    if not saved.exists():
        saved.write_text(current + "\n")
        saved.chmod(0o600)
    return current


def main():
    root = Path(__file__).resolve().parent
    config = json.loads((root / "runtime.json").read_text())
    os.umask(0o077)
    secret_dir = Path("/run/travelpay/redis")
    secret_dir.mkdir(parents=True, exist_ok=True)
    secret_dir.chmod(0o700)
    # Redis 공식 이미지의 redis 사용자 uid/gid입니다. bind된 파일은 해당 사용자만 읽습니다.
    os.chown(secret_dir, 999, 999)
    for kind, name in [("session", "REDIS_SESSION_PASSWORD"), ("cache", "REDIS_CACHE_PASSWORD")]:
        response = subprocess.check_output([
            "aws", "ssm", "get-parameter", "--region", config["region"], "--name",
            config["parameter_prefix"] + "/" + name, "--with-decryption", "--output", "json",
        ], text=True, stderr=subprocess.PIPE, timeout=30)
        password = json.loads(response)["Parameter"]["Value"]
        target = secret_dir / (kind + ".conf")
        target.write_text(redis_config(password))
        os.chown(target, 999, 999)
        target.chmod(0o400)
    kafka_root = Path(config["volumes"]["kafka"]["mount"])
    if not kafka_root.is_mount():
        raise PreparationError("Kafka EBS가 마운트되지 않았습니다.")
    identity = cluster_id(kafka_root)
    data = kafka_root / "data"
    data.mkdir(exist_ok=True)
    os.chown(data, 1000, 1000)
    data.chmod(0o750)
    # 사설 IP만 Docker에 공개합니다. IMDS 요청은 호스트 프로세스가 IMDSv2로 수행합니다.
    import urllib.request
    token_request = urllib.request.Request("http://169.254.169.254/latest/api/token", method="PUT",
                                          headers={"X-aws-ec2-metadata-token-ttl-seconds": "60"})
    with urllib.request.urlopen(token_request, timeout=5) as response:
        token = response.read().decode()
    ip_request = urllib.request.Request("http://169.254.169.254/latest/meta-data/local-ipv4",
                                       headers={"X-aws-ec2-metadata-token": token})
    with urllib.request.urlopen(ip_request, timeout=5) as response:
        address = response.read().decode().strip()
    import ipaddress
    if not ipaddress.IPv4Address(address).is_private:
        raise PreparationError("Data 호스트의 사설 IPv4를 확인할 수 없습니다.")
    (root / "runtime.env").write_text(f"DATA_PRIVATE_IP={address}\nKAFKA_CLUSTER_ID={identity}\n")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # AWS 오류와 비밀값이 포함될 수 있는 응답·명령 인자는 로그에 내보내지 않습니다.
        print(failure_message(error), file=sys.stderr)
        sys.exit(1)
