#!/usr/bin/env python3
"""확인된 설정 묶음을 Data 호스트에 설치하고 바뀐 부분만 반영합니다. fetch-config-bundle.py가 실행합니다."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request


class ApplyError(Exception):
    pass


DATA_FILES = ("runtime.json", "compose.yaml", "monitoring-compose.yaml", "mount-data-volumes.py", "prepare-data.py",
              "prepare-monitoring.py", "bootstrap-database.py", "apply-data-bundle.py")
# Redis·Kafka를 먼저 반영합니다. 모니터링 반영이 실패해도 Redis·Kafka는 반영된 채로 남고 적용 실패로 드러납니다.
UNITS = ("travelpay-data.service", "travelpay-monitoring.service")
# 모니터링 설정은 비밀값이 없고 root가 아닌 컨테이너 사용자가 읽어야 하므로 별도 경로에 0644로 둡니다.
MONITORING_FILES = {
    "prometheus.yml": "prometheus/prometheus.yml",
    "loki.yml": "loki/loki.yml",
    "data.alloy": "alloy/config.alloy",
    "grafana-datasources.yml": "grafana/provisioning/datasources/datasources.yml",
    "grafana-dashboards.yml": "grafana/provisioning/dashboards/dashboards.yml",
    "grafana-alert-transfer.yml": "grafana/provisioning/alerting/transfer-alerts.yml",
    "grafana-alert-operations.yml": "grafana/provisioning/alerting/operations-alerts.yml",
    "dashboard-production.json": "grafana/dashboards/travelpay-production.json",
}


class Paths:
    def __init__(self, root=Path("/"), data=None):
        self.data = data or root / "opt/travelpay/data"
        self.monitoring = root / "opt/travelpay/monitoring"
        self.units = root / "etc/systemd/system"
        self.cloudwatch = root / "opt/aws/amazon-cloudwatch-agent/etc/travelpay.json"
        self.compose = root / "usr/local/lib/docker/cli-plugins/docker-compose"


def failure_message(error):
    reason = str(error) if isinstance(error, ApplyError) else type(error).__name__
    return f"Data bundle apply failed: {reason}"


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


def install_readable(source, root, relative):
    """모니터링 설정을 root 아래에 폴더 0755·파일 0644로 설치합니다. 폴더째 마운트하는 Grafana 설정은 컨테이너 사용자가
    읽어야 합니다. mkdir의 mode는 umask가 걸러 내므로, 이 스크립트가 관리하는 root 아래 폴더에만 권한을 직접 지정합니다."""
    directories = [root]
    for part in Path(relative).parts[:-1]:
        directories.append(directories[-1] / part)
    for directory in directories:
        directory.mkdir(parents=True, exist_ok=True)
        directory.chmod(0o755)
    install(source, root / relative, 0o644)


def sha256_of(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None


def download(run, url, target, timeout):
    run(["curl", "--fail", "--silent", "--show-error", "--location", "--retry", "3", "--max-time", str(timeout),
         url, "-o", str(target)], check=True, timeout=timeout + 30)


def ensure_compose(run, config, path):
    expected = config["compose_sha256"]
    if sha256_of(path) == expected:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".download")
    download(run, "https://github.com/docker/compose/releases/download/"
             f"{config['compose_version']}/docker-compose-linux-x86_64", temporary, 120)
    if sha256_of(temporary) != expected:
        temporary.unlink()
        raise ApplyError("Docker Compose 바이너리의 SHA-256이 고정값과 다릅니다.")
    temporary.chmod(0o755)
    os.replace(temporary, path)
    return True


def ensure_certificate(run, config, path):
    # 내려받기가 중간에 끊긴 파일을 남기지 않도록 임시 파일에서 확인한 뒤 교체합니다.
    if path.exists() and b"BEGIN CERTIFICATE" in path.read_bytes():
        return False
    region = config["region"]
    temporary = path.with_name(path.name + ".download")
    download(run, f"https://truststore.pki.rds.amazonaws.com/{region}/{region}-bundle.pem", temporary, 60)
    if b"BEGIN CERTIFICATE" not in temporary.read_bytes():
        temporary.unlink()
        raise ApplyError("RDS CA 번들이 인증서 형식이 아닙니다.")
    os.replace(temporary, path)
    return True


def readiness_urls(runtime_env):
    """Loki만 앱 호스트가 보내는 사설 IP로 열고 나머지는 호스트 루프백으로만 엽니다(monitoring-compose.yaml)."""
    values = dict(line.split("=", 1) for line in runtime_env.splitlines() if "=" in line)
    return {
        "Prometheus": "http://127.0.0.1:9090/-/ready",
        "Loki": f"http://{values['DATA_PRIVATE_IP']}:3100/ready",
        "Grafana": "http://127.0.0.1:3000/api/health",
        "Alloy": "http://127.0.0.1:12345/-/ready",
    }


def wait_until_ready(urls, opener=urllib.request.urlopen, clock=time.monotonic, sleep=time.sleep, timeout=300):
    # Compose는 상태 확인이 없는 컨테이너(Loki·Alloy 이미지에는 HTTP 도구가 없음)를 시작만 확인하므로 응답까지 확인합니다.
    pending = dict(urls)
    deadline = clock() + timeout
    while True:
        for name, url in list(pending.items()):
            try:
                with opener(url, timeout=5) as response:
                    if response.status == 200:
                        del pending[name]
            except OSError:
                pass
        if not pending:
            return
        if clock() >= deadline:
            raise ApplyError("제한 시간 안에 준비되지 않은 모니터링 구성 요소: " + ", ".join(sorted(pending)))
        sleep(5)


def apply(source, run=subprocess.run, paths=None, wait=wait_until_ready):
    paths = paths or Paths()
    config = json.loads((source / "runtime.json").read_text())
    paths.data.mkdir(mode=0o700, parents=True, exist_ok=True)
    for name in DATA_FILES:
        install(source / name, paths.data / name, 0o600)
    for name, relative in MONITORING_FILES.items():
        install_readable(source / name, paths.monitoring, relative)
    ensure_compose(run, config, paths.compose)
    ensure_certificate(run, config, paths.data / "rds-ca.pem")
    # EBS가 확인되기 전에는 Kafka·모니터링 경로를 쓰지 않습니다.
    run([sys.executable, str(paths.data / "mount-data-volumes.py")], check=True, timeout=600)
    # 아래 반영 단계는 파일이 바뀌지 않았어도 매번 실행합니다. 이전 실행이 설치 후 실패했다면 재시도에서 다시 반영해야 합니다.
    install(source / "cloudwatch-agent.json", paths.cloudwatch, 0o600)
    run(["/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl", "-a", "fetch-config", "-m", "ec2",
         "-c", f"file:{paths.cloudwatch}", "-s"], check=True, timeout=120)
    for unit in UNITS:
        install(source / unit, paths.units / unit, 0o644)
    run(["systemctl", "daemon-reload"], check=True, timeout=60)
    # 반영 전에 두 유닛을 모두 켜 둡니다. Redis·Kafka 반영이 실패해도 모니터링 유닛은 다음 부팅에 시작됩니다.
    for unit in UNITS:
        run(["systemctl", "enable", unit], check=True, timeout=60)
        # 이전 실패의 자동 재시작이 시작 횟수 제한을 다 썼어도 이번 적용은 시작할 수 있게 합니다.
        run(["systemctl", "reset-failed", unit], check=True, timeout=60)
    for unit in UNITS:
        # 실행 중이면 ExecReload로 바뀐 컨테이너만 다시 만들고, 멈춰 있으면 시작합니다.
        run(["systemctl", "reload-or-restart", unit], check=True, timeout=1000)
    wait(readiness_urls((paths.data / "runtime.env").read_text()))


if __name__ == "__main__":
    try:
        apply(Path(sys.argv[1]))
    except Exception as error:
        print(failure_message(error), file=sys.stderr)
        sys.exit(1)
