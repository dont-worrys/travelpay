"""선택 실행: TRAVELPAY_TEST_DOCKER_MONITORING=1. 운영 Alloy·Loki 설정으로 실제 컨테이너 로그가 레이블과 함께 저장되는지 검사합니다.

설정 문법 검사와 Terraform 모의 테스트로는 드러나지 않았던 결함(권한 없는 root의 저장 경로 접근 실패,
relabel_rules의 drop이 레이블만 지우고 pause 로그를 보내던 문제)을 실제 실행으로 다시 확인합니다.
"""

import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import unittest
import urllib.parse
import uuid

ROOT = Path(__file__).parents[1]
TEMPLATES = ROOT / "templates"
# 운영에 쓰는 고정 이미지(monitoring.tf)를 그대로 씁니다.
IMAGES = dict(re.findall(r'^\s+(prometheus|loki|grafana|alloy)\s+= "([^"]+@sha256:[0-9a-f]{64})"$',
                         (ROOT / "monitoring.tf").read_text(), re.M))
PROBE = "busybox:1.37.0"
spec = importlib.util.spec_from_file_location("monitoring_preparation", ROOT / "scripts" / "prepare-monitoring.py")
preparation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preparation)


def docker(*arguments, check=True):
    return subprocess.run(["docker", *arguments], capture_output=True, text=True, timeout=180, check=check).stdout


@unittest.skipUnless(os.environ.get("TRAVELPAY_TEST_DOCKER_MONITORING") == "1", "모니터링 실행 검사는 명시적으로 실행합니다.")
class MonitoringPipelineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # 정리는 addClassCleanup으로 등록합니다. setUpClass가 실패하면 tearDownClass는 불리지 않지만 등록한 정리는 실행됩니다.
        cls.suffix = uuid.uuid4().hex[:8]
        cls.network = "travelpay-monitoring-test-" + cls.suffix
        cls.work = Path(os.environ.get("TMPDIR", "/tmp")) / ("travelpay-monitoring-" + cls.suffix)
        cls.work.mkdir()
        cls.addClassCleanup(shutil.rmtree, cls.work)
        app_config = (TEMPLATES / "monitoring" / "app.alloy.tftpl").read_text()
        app_config = app_config.replace("${loki_host}", "data.travelpay.internal").replace("${loki_port}", "3100")
        (cls.work / "app.alloy").write_text(app_config)
        docker("network", "create", "--internal", cls.network)
        cls.addClassCleanup(docker, "network", "rm", cls.network, check=False)
        cls.start("loki", "--network-alias", "data.travelpay.internal", "--network-alias", "loki",
                  "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                  "-v", f"{TEMPLATES / 'monitoring' / 'loki.yml'}:/etc/loki/loki.yml:ro", IMAGES["loki"],
                  "-config.file=/etc/loki/loki.yml")
        cls.wait(lambda: cls.loki("ready", raw=True) == "ready")
        labels = {
            # ECS Task는 awslogs(원격 드라이버)를 쓰므로 Alloy는 Docker의 로컬 캐시(dual logging)를 읽습니다. 같은 경로를 쓰도록
            # 원격 드라이버(UDP syslog, 받는 곳 없음)와 Task 정의와 같은 non-blocking으로 실행합니다.
            "task": ["--label", "com.amazonaws.ecs.task-arn=arn:aws:ecs:ap-northeast-2:123456789012:task/travelpay-production/t",
                     "--label", "com.amazonaws.ecs.container-name=payment-service",
                     "--log-driver", "syslog", "--log-opt", "syslog-address=udp://127.0.0.1:5514", "--log-opt", "mode=non-blocking"],
            "pause": ["--label", "com.amazonaws.ecs.task-arn=arn:aws:ecs:ap-northeast-2:123456789012:task/travelpay-production/t",
                      "--label", "com.amazonaws.ecs.container-name=~internal~ecs~pause"],
            "kafka": ["--label", "com.docker.compose.project=travelpay-production-data", "--label", "com.docker.compose.service=kafka"],
            "grafana": ["--label", "com.docker.compose.project=travelpay-production-monitoring",
                        "--label", "com.docker.compose.service=grafana"],
            "other": ["--label", "com.docker.compose.project=travelpay-other", "--label", "com.docker.compose.service=other-service"],
        }
        for name, flags in labels.items():
            cls.start(name, "--network", "none", *flags, PROBE, "sh", "-c", f"while true; do echo {name}-line; sleep 1; done", network=False)
        # 운영 유닛·Compose와 같은 권한 제한과 저장 경로로 실행합니다.
        for name, config in [("alloy-app", cls.work / "app.alloy"), ("alloy-data", TEMPLATES / "monitoring" / "data.alloy")]:
            cls.start(name, "--memory", "256m", "--security-opt", "no-new-privileges", "--cap-drop", "ALL",
                      "-v", "/var/run/docker.sock:/var/run/docker.sock:ro", "-v", f"{config}:/etc/alloy/config.alloy:ro",
                      "--mount", "type=volume,dst=/alloy-data", IMAGES["alloy"], "run", "--server.http.listen-addr=0.0.0.0:12345",
                      "--storage.path=/alloy-data", "--disable-reporting", "/etc/alloy/config.alloy")
        cls.wait(lambda: {"payment-service", "kafka", "grafana"} <= set(cls.loki("label/service_name/values").get("data") or []))

    @classmethod
    def start(cls, name, *arguments, network=True):
        container = f"travelpay-{name}-{cls.suffix}"
        # 정리는 나중에 등록한 것부터 실행되므로 컨테이너가 네트워크보다 먼저 지워집니다.
        cls.addClassCleanup(docker, "rm", "-f", "-v", container, check=False)
        docker("run", "-d", "--name", container, *(["--network", cls.network] if network else []), *arguments)

    @classmethod
    def loki(cls, path, raw=False):
        body = docker("run", "--rm", "--network", cls.network, PROBE, "wget", "-q", "-O", "-", "http://loki:3100/" +
                      (path if raw else "loki/api/v1/" + path), check=False).strip()
        return body if raw else (json.loads(body) if body.startswith("{") else {})

    @staticmethod
    def wait(condition, timeout=120):
        deadline = time.monotonic() + timeout
        while not condition():
            if time.monotonic() > deadline:
                raise AssertionError("제한 시간 안에 조건을 만족하지 못했습니다.")
            time.sleep(2)

    def series(self, selector):
        return self.loki("series?match%5B%5D=" + urllib.parse.quote(selector)).get("data") or []

    def test_task_and_data_logs_carry_the_dashboard_labels(self):
        self.assertIn({"job": "ecs-tasks", "environment": "production", "service_name": "payment-service"},
                      [{k: s[k] for k in ("job", "environment", "service_name") if k in s} for s in self.series('{job="ecs-tasks"}')])
        # Data 호스트는 Redis·Kafka와 모니터링 두 Compose 프로젝트의 로그만 보냅니다. 이 PC의 Docker를 함께 쓰므로 같은
        # 프로젝트 레이블의 다른 컨테이너가 더 보일 수 있어, 두 프로젝트의 시험 컨테이너가 있고 다른 프로젝트가 없는지만 봅니다.
        services = {s.get("service_name") for s in self.series('{job="docker-data"}')}
        self.assertLessEqual({"grafana", "kafka"}, services)
        self.assertNotIn("other-service", services)

    def test_pause_container_logs_are_not_collected_even_without_labels(self):
        self.assertEqual(self.series('{service_name="unknown_service"}'), [])
        self.assertNotIn("~internal~ecs~pause", self.loki("label/service_name/values").get("data") or [])

    def test_image_users_match_the_prepared_folder_owners(self):
        # prepare-monitoring.py가 이 uid로 폴더를 만들어야 각 컨테이너가 자기 데이터 폴더에 씁니다.
        for name, (uid, _, _) in preparation.MONITORING_DIRECTORIES.items():
            # image inspect는 이미지를 받지 않습니다. 앞에서 실행하지 않은 Prometheus·Grafana는 새 CI 실행기에 없으므로 먼저 받습니다.
            docker("pull", "--quiet", IMAGES[name])
            user = docker("image", "inspect", "-f", "{{.Config.User}}", IMAGES[name]).strip().split(":")[0]
            if user and not user.isdigit():
                user = docker("run", "--rm", "--network", "none", "--entrypoint", "id", IMAGES[name], "-u", user).strip()
            with self.subTest(image=name):
                self.assertEqual(int(user or 0), uid)

    def test_collectors_keep_running_with_all_capabilities_dropped(self):
        for name in ("alloy-app", "alloy-data"):
            with self.subTest(collector=name):
                self.assertEqual(docker("inspect", "-f", "{{.State.Running}}", f"travelpay-{name}-{self.suffix}").strip(), "true")


if __name__ == "__main__":
    unittest.main()
