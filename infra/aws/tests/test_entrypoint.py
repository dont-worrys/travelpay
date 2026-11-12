"""컨테이너 시작 스크립트의 종료 순서를 실제 프로세스로 검사합니다. ECS 메타데이터·Eureka는 로컬 HTTP 서버로 대신합니다."""

from http.server import BaseHTTPRequestHandler, HTTPServer
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import threading
import time
import unittest

SCRIPT = Path(__file__).parents[3] / "deploy" / "docker" / "entrypoint.sh"

# 받은 인자와 종료 신호 시각을 기록하는 가짜 java입니다.
FAKE_JAVA = """#!/usr/bin/env bash
echo "args $*" >> "$JAVA_LOG"
echo "ip ${EUREKA_INSTANCE_IP_ADDRESS:-none} id ${EUREKA_INSTANCE_INSTANCEID:-none}" >> "$JAVA_LOG"
trap 'echo "term $(date +%s.%N)" >> "$JAVA_LOG"; exit 143' TERM
while true; do sleep 0.1; done
"""


class FakeAws(BaseHTTPRequestHandler):
    metadata = b'{"Networks":[{"NetworkMode":"awsvpc","IPv4Addresses":["10.20.10.7"]}]}'
    eureka_status = 200
    requests = []

    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(type(self).metadata)

    def do_PUT(self):
        type(self).requests.append((self.path, time.time()))
        self.send_response(type(self).eureka_status)
        self.end_headers()

    def log_message(self, *_):
        pass


class EntrypointTest(unittest.TestCase):
    def setUp(self):
        FakeAws.requests = []
        FakeAws.eureka_status = 200
        FakeAws.metadata = b'{"Networks":[{"NetworkMode":"awsvpc","IPv4Addresses":["10.20.10.7"]}]}'
        self.server = HTTPServer(("127.0.0.1", 0), FakeAws)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.work = tempfile.TemporaryDirectory()
        bin_dir = Path(self.work.name) / "bin"
        bin_dir.mkdir()
        (bin_dir / "java").write_text(FAKE_JAVA)
        (bin_dir / "java").chmod(0o755)
        self.log = Path(self.work.name) / "java.log"
        base = f"http://127.0.0.1:{self.server.server_port}"
        self.env = dict(os.environ, PATH=f"{bin_dir}:{os.environ['PATH']}", JAVA_LOG=str(self.log),
                        ECS_CONTAINER_METADATA_URI_V4=f"{base}/v4", TRAVELPAY_EUREKA_URL=f"{base}/eureka/",
                        TRAVELPAY_EUREKA_APP="USER-SERVICE", TRAVELPAY_SERVICE_PORT="8080", TRAVELPAY_DRAIN_SECONDS="1")

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.work.cleanup()

    def start(self, env):
        process = subprocess.Popen(["bash", str(SCRIPT)], env=env)
        for _ in range(100):
            if self.log.exists() and "args" in self.log.read_text():
                return process
            time.sleep(0.05)
        process.kill()
        self.fail("가짜 java가 시작되지 않았습니다.")

    def test_arguments_replace_the_application(self):
        result = subprocess.run(["bash", str(SCRIPT), "echo", "smoke-ok"], env=self.env, capture_output=True, text=True, timeout=10)
        self.assertEqual(result.stdout.strip(), "smoke-ok")
        self.assertFalse(self.log.exists())

    def test_service_without_eureka_runs_java_directly(self):
        env = dict(self.env)
        del env["TRAVELPAY_EUREKA_URL"]
        process = self.start(env)
        process.send_signal(signal.SIGTERM)
        self.assertEqual(process.wait(timeout=10), 143)
        self.assertIn("args -jar /opt/travelpay/app.jar", self.log.read_text())
        self.assertEqual(FakeAws.requests, [])

    def test_sigterm_marks_out_of_service_before_stopping_java(self):
        process = self.start(self.env)
        signalled = time.time()
        process.send_signal(signal.SIGTERM)
        self.assertEqual(process.wait(timeout=10), 143)
        log = self.log.read_text()
        self.assertIn("ip 10.20.10.7 id 10.20.10.7:8080", log)
        self.assertEqual([path for path, _ in FakeAws.requests],
                         ["/eureka/apps/USER-SERVICE/10.20.10.7:8080/status?value=OUT_OF_SERVICE"])
        terminated = float(log.split("term ")[1].split()[0])
        self.assertGreaterEqual(terminated - signalled, 1.0)
        self.assertGreater(terminated, FakeAws.requests[0][1])

    def test_missing_task_ip_fails_before_starting_java(self):
        FakeAws.metadata = b'{"Networks":[]}'
        result = subprocess.run(["bash", str(SCRIPT)], env=self.env, capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 1)
        self.assertFalse(self.log.exists())

    def test_eureka_failure_still_stops_java_after_waiting(self):
        FakeAws.eureka_status = 500
        process = self.start(self.env)
        process.send_signal(signal.SIGTERM)
        self.assertEqual(process.wait(timeout=10), 143)
        self.assertEqual(len(FakeAws.requests), 1)
        self.assertIn("term ", self.log.read_text())


if __name__ == "__main__":
    unittest.main()
