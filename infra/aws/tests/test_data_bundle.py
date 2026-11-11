"""설정 묶음의 해시 확인·설치·반영 순서를 가짜 명령 실행기로 검사합니다. 실제 AWS·systemd는 호출하지 않습니다."""

import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


def load(name):
    path = Path(__file__).parents[1] / "scripts" / (name + ".py")
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fetch = load("fetch-config-bundle")
apply = load("apply-data-bundle")

COMPOSE_BINARY = b"compose-binary"
FILES = {
    "runtime.json": json.dumps({"region": "ap-northeast-2", "compose_version": "v5.7.0",
                                "compose_sha256": hashlib.sha256(COMPOSE_BINARY).hexdigest()}),
    "compose.yaml": "services: {}\n", "monitoring-compose.yaml": "services: {}\n",
    "travelpay-data.service": "[Unit]\n", "travelpay-monitoring.service": "[Unit]\n",
    "cloudwatch-agent.json": "{}",
    "mount-data-volumes.py": "", "prepare-data.py": "", "prepare-monitoring.py": "", "bootstrap-database.py": "",
    "apply-data-bundle.py": "",
    "prometheus.yml": "global: {}\n", "loki.yml": "auth_enabled: false\n", "data.alloy": "// alloy\n",
    "grafana-datasources.yml": "apiVersion: 1\n", "grafana-dashboards.yml": "apiVersion: 1\n",
    "grafana-alert-transfer.yml": "apiVersion: 1\n", "grafana-alert-operations.yml": "apiVersion: 1\n",
    "dashboard-production.json": "{}",
}
REQUEST = {"TRAVELPAY_BUNDLE_BUCKET": "b", "TRAVELPAY_BUNDLE_KEY": "k", "TRAVELPAY_REGION": "r",
           "TRAVELPAY_APPLY_SCRIPT": "apply-data-bundle.py"}


def bundle(files=FILES):
    document = json.dumps(files).encode()
    return gzip.compress(document), hashlib.sha256(document).hexdigest()


class Recorder:
    """명령을 기록하고 다운로드는 지정한 내용으로 파일을 만듭니다."""

    def __init__(self, downloads=None, failing=()):
        # failing의 단어가 모두 든 명령을 실패시킵니다.
        self.commands, self.downloads, self.failing = [], downloads or {}, failing

    def __call__(self, command, check=False, **_):
        self.commands.append(command)
        if command[0] == "curl":
            Path(command[command.index("-o") + 1]).write_bytes(self.downloads.get(command[-3], b"-----BEGIN CERTIFICATE-----"))
        if self.failing and all(word in command for word in self.failing) and check:
            raise subprocess.CalledProcessError(1, command)
        return subprocess.CompletedProcess(command, 0, "", "")

    def ran(self, *words):
        return [command for command in self.commands if all(word in command for word in words)]


class FetchTest(unittest.TestCase):
    def test_rejects_bundle_whose_hash_differs_from_terraform(self):
        compressed, _ = bundle()
        with tempfile.TemporaryDirectory() as work, self.assertRaises(fetch.BundleError):
            fetch.unpack(compressed, "0" * 64, Path(work), "apply-data-bundle.py")

    def test_rejects_paths_outside_the_staging_directory(self):
        compressed, digest = bundle(dict(FILES, **{"../escape": "x"}))
        with tempfile.TemporaryDirectory() as work, self.assertRaises(fetch.BundleError):
            fetch.unpack(compressed, digest, Path(work), "apply-data-bundle.py")

    def test_rejects_bundle_without_the_requested_apply_script(self):
        compressed, digest = bundle()
        with tempfile.TemporaryDirectory() as work, self.assertRaises(fetch.BundleError):
            fetch.unpack(compressed, digest, Path(work), "apply-app-bundle.py")

    def test_rejects_malformed_request(self):
        for environ in [{}, dict(REQUEST, TRAVELPAY_BUNDLE_SHA256="not-a-hash"),
                        # 묶음 안의 임의 파일이나 경로를 실행하지 못하게 두 적용 스크립트 이름만 받습니다.
                        dict(REQUEST, TRAVELPAY_BUNDLE_SHA256="0" * 64, TRAVELPAY_APPLY_SCRIPT="bootstrap-database.py"),
                        dict(REQUEST, TRAVELPAY_BUNDLE_SHA256="0" * 64, TRAVELPAY_APPLY_SCRIPT="../apply-data-bundle.py")]:
            with self.subTest(environ=environ), self.assertRaises(fetch.BundleError):
                fetch.read_request(environ)

    def test_downloads_verifies_and_runs_bundled_apply_script(self):
        compressed, digest = bundle()
        environ = {"TRAVELPAY_BUNDLE_BUCKET": "travelpay-production-data-config-123456789012",
                   "TRAVELPAY_BUNDLE_KEY": "data/bundle.json.gz", "TRAVELPAY_BUNDLE_SHA256": digest,
                   "TRAVELPAY_REGION": "ap-northeast-2", "TRAVELPAY_APPLY_SCRIPT": "apply-data-bundle.py"}
        commands = []

        def run(command, **_):
            commands.append(command)
            if command[:3] == ["aws", "s3api", "get-object"]:
                Path(command[-1]).write_bytes(compressed)
            if command[-2:-1] and command[-2].endswith("apply-data-bundle.py"):
                self.assertEqual(sorted(path.name for path in Path(command[-1]).iterdir()), sorted(FILES))
            return subprocess.CompletedProcess(command, 0)

        fetch.main(environ, run, which=lambda tool: "/usr/bin/" + tool)
        self.assertEqual(commands[0], ["cloud-init", "status", "--wait"])
        self.assertIn("--bucket", commands[1])
        self.assertTrue(commands[2][-2].endswith("apply-data-bundle.py"))

    def download(self, errors):
        """get-object가 errors의 오류를 차례로 낸 뒤 성공하는 실행기로 fetch.main을 돌리고 명령 목록을 돌려줍니다."""
        compressed, digest = bundle()
        environ = dict(REQUEST, TRAVELPAY_BUNDLE_SHA256=digest)
        commands, pending = [], list(errors)

        def run(command, **_):
            commands.append(command)
            if command[:3] == ["aws", "s3api", "get-object"]:
                if pending:
                    return subprocess.CompletedProcess(command, 255, "", pending.pop(0))
                Path(command[-1]).write_bytes(compressed)
            return subprocess.CompletedProcess(command, 0, "", "")

        try:
            fetch.main(environ, run, which=lambda tool: "/usr/bin/" + tool, sleep=lambda _: None)
        finally:
            self.downloads = [command for command in commands if command[:3] == ["aws", "s3api", "get-object"]]
        return commands

    def test_reports_the_aws_error_code_when_download_fails(self):
        denied = "An error occurred (AccessDenied) when calling the GetObject operation: Access Denied"
        with self.assertRaisesRegex(fetch.BundleError, "AccessDenied$"):
            self.download([denied] * fetch.DOWNLOAD_ATTEMPTS)
        self.assertEqual(len(self.downloads), fetch.DOWNLOAD_ATTEMPTS)

    def test_retries_access_denied_while_new_permission_propagates(self):
        denied = "An error occurred (AccessDenied) when calling the GetObject operation: Access Denied"
        commands = self.download([denied, denied])
        self.assertEqual(len(self.downloads), 3)
        self.assertTrue(commands[-1][-2].endswith("apply-data-bundle.py"))

    def test_other_download_errors_fail_without_retry(self):
        missing = "An error occurred (NoSuchBucket) when calling the GetObject operation: The bucket does not exist"
        with self.assertRaisesRegex(fetch.BundleError, "NoSuchBucket$"):
            self.download([missing])
        self.assertEqual(len(self.downloads), 1)

    def test_script_embedded_in_ssm_document_has_no_parameter_placeholders(self):
        # SSM은 문서의 이중 중괄호를 매개변수로 치환하므로 내장 스크립트에 들어가면 안 됩니다.
        script = (Path(__file__).parents[1] / "scripts" / "fetch-config-bundle.py").read_text()
        self.assertNotIn("{" * 2, script)
        self.assertNotIn("}" * 2, script)

    def test_stops_before_download_when_tools_are_missing(self):
        _, digest = bundle()
        environ = dict(REQUEST, TRAVELPAY_BUNDLE_SHA256=digest)
        commands = []
        with self.assertRaises(fetch.BundleError):
            fetch.main(environ, lambda command, **_: commands.append(command), which=lambda tool: None)
        self.assertEqual(commands, [["cloud-init", "status", "--wait"]])


class ApplyTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.root = Path(self.work.name)
        self.source = self.root / "bundle"
        self.source.mkdir()
        for name, contents in FILES.items():
            (self.source / name).write_text(contents)
        self.paths = apply.Paths(root=self.root / "host")
        self.compose_url = "https://github.com/docker/compose/releases/download/v5.7.0/docker-compose-linux-x86_64"
        # prepare-data.py가 ExecReload 중에 쓰는 파일입니다. 가짜 실행기는 systemd를 실행하지 않으므로 미리 둡니다.
        self.paths.data.mkdir(parents=True)
        (self.paths.data / "runtime.env").write_text("DATA_PRIVATE_IP=10.20.20.10\nKAFKA_CLUSTER_ID=x\n")
        self.waited = []

    def apply(self, run):
        apply.apply(self.source, run, self.paths, wait=self.waited.append)

    def tearDown(self):
        self.work.cleanup()

    def test_first_apply_installs_everything_and_starts_the_service(self):
        run = Recorder({self.compose_url: COMPOSE_BINARY})
        self.apply(run)
        self.assertEqual((self.paths.data / "compose.yaml").read_text(), FILES["compose.yaml"])
        self.assertEqual(oct((self.paths.data / "runtime.json").stat().st_mode & 0o777), "0o600")
        self.assertEqual(self.paths.compose.read_bytes(), COMPOSE_BINARY)
        self.assertEqual(len(run.ran("fetch-config")), 1)
        self.assertEqual(len(run.ran("daemon-reload")), 1)
        self.assertEqual(run.commands[-6:], [
            ["systemctl", "enable", "travelpay-data.service"], ["systemctl", "reset-failed", "travelpay-data.service"],
            ["systemctl", "enable", "travelpay-monitoring.service"],
            ["systemctl", "reset-failed", "travelpay-monitoring.service"],
            ["systemctl", "reload-or-restart", "travelpay-data.service"],
            ["systemctl", "reload-or-restart", "travelpay-monitoring.service"]])

    def test_retry_with_same_bundle_reapplies_host_steps_without_downloading(self):
        # 첫 실행이 파일 설치 뒤 반영 단계에서 실패해도, 같은 묶음의 재시도가 그 단계를 다시 실행해야 합니다.
        with self.assertRaises(subprocess.CalledProcessError):
            self.apply(Recorder({self.compose_url: COMPOSE_BINARY}, failing=("fetch-config",)))
        again = Recorder()
        self.apply(again)
        self.assertEqual(again.ran("curl"), [])
        self.assertEqual(len(again.ran("fetch-config")), 1)
        self.assertEqual(len(again.ran("daemon-reload")), 1)
        self.assertEqual(again.ran("reload-or-restart"), [["systemctl", "reload-or-restart", "travelpay-data.service"],
                                                          ["systemctl", "reload-or-restart", "travelpay-monitoring.service"]])

    def test_partial_certificate_is_downloaded_again(self):
        (self.paths.data / "rds-ca.pem").write_bytes(b"truncated")
        run = Recorder({self.compose_url: COMPOSE_BINARY})
        self.apply(run)
        self.assertIn(b"BEGIN CERTIFICATE", (self.paths.data / "rds-ca.pem").read_bytes())

    def test_rejects_certificate_download_that_is_not_pem(self):
        url = "https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem"
        with self.assertRaises(apply.ApplyError):
            self.apply(Recorder({self.compose_url: COMPOSE_BINARY, url: b"<html>error</html>"}))
        self.assertFalse((self.paths.data / "rds-ca.pem").exists())

    def test_rejects_compose_binary_with_unexpected_hash(self):
        with self.assertRaises(apply.ApplyError):
            self.apply(Recorder({self.compose_url: b"tampered"}))
        self.assertFalse(self.paths.compose.exists())

    def test_failed_reload_is_reported_to_ssm(self):
        run = Recorder({self.compose_url: COMPOSE_BINARY}, failing=("reload-or-restart",))
        with self.assertRaises(subprocess.CalledProcessError):
            self.apply(run)

    def test_monitoring_failure_is_reported_after_redis_and_kafka_are_applied(self):
        # 모니터링 유닛 반영이 실패해도 Redis·Kafka 유닛은 이미 반영됐고, 적용은 실패로 SSM에 드러납니다.
        run = Recorder({self.compose_url: COMPOSE_BINARY}, failing=("reload-or-restart", "travelpay-monitoring.service"))
        with self.assertRaises(subprocess.CalledProcessError):
            self.apply(run)
        self.assertEqual(run.ran("reload-or-restart", "travelpay-data.service"),
                         [["systemctl", "reload-or-restart", "travelpay-data.service"]])
        self.assertEqual(self.waited, [])

    def test_failed_redis_kafka_reload_still_enables_monitoring_for_the_next_boot(self):
        run = Recorder({self.compose_url: COMPOSE_BINARY}, failing=("reload-or-restart", "travelpay-data.service"))
        with self.assertRaises(subprocess.CalledProcessError):
            self.apply(run)
        self.assertEqual(run.ran("enable", "travelpay-monitoring.service"), [["systemctl", "enable", "travelpay-monitoring.service"]])
        self.assertEqual(run.ran("reload-or-restart", "travelpay-monitoring.service"), [])

    def test_leaves_os_owned_folder_permissions_alone(self):
        # 유닛 폴더와 CloudWatch Agent 설정 폴더는 OS·패키지 소유이므로 권한을 바꾸지 않습니다.
        folders = (self.paths.units, self.paths.cloudwatch.parent)
        for folder in folders:
            folder.mkdir(parents=True)
            folder.chmod(0o755)
        self.apply(Recorder({self.compose_url: COMPOSE_BINARY}))
        for folder in folders:
            with self.subTest(folder=folder.name):
                self.assertEqual(oct(folder.stat().st_mode & 0o777), "0o755")

    def test_monitoring_files_are_installed_readable_for_container_users(self):
        # 실행 환경의 umask가 엄격해도(077) root가 아닌 컨테이너 사용자가 설정 폴더·파일을 읽을 수 있어야 합니다.
        previous = os.umask(0o077)
        try:
            self.apply(Recorder({self.compose_url: COMPOSE_BINARY}))
        finally:
            os.umask(previous)
        for name, relative in apply.MONITORING_FILES.items():
            target = self.paths.monitoring / relative
            with self.subTest(name=name):
                self.assertEqual(target.read_text(), FILES[name])
                self.assertEqual(oct(target.stat().st_mode & 0o777), "0o644")
                self.assertEqual(oct(target.parent.stat().st_mode & 0o777), "0o755")

    def test_monitoring_readiness_is_checked_after_reload_with_the_private_loki_address(self):
        run = Recorder({self.compose_url: COMPOSE_BINARY})
        self.apply(run)
        self.assertEqual(run.commands[-1], ["systemctl", "reload-or-restart", "travelpay-monitoring.service"])
        self.assertEqual(self.waited, [apply.readiness_urls("DATA_PRIVATE_IP=10.20.20.10\n")])
        self.assertEqual(self.waited[0]["Loki"], "http://10.20.20.10:3100/ready")
        self.assertTrue(all(url.startswith("http://127.0.0.1:") for name, url in self.waited[0].items() if name != "Loki"))


class ReadinessTest(unittest.TestCase):
    class Response:
        status = 200

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

    def test_returns_when_every_component_answers(self):
        calls = []

        def opener(url, timeout):
            calls.append(url)
            return self.Response()

        apply.wait_until_ready({"A": "http://a", "B": "http://b"}, opener=opener, clock=lambda: 0, sleep=lambda _: None)
        self.assertEqual(sorted(calls), ["http://a", "http://b"])

    def test_names_only_the_components_that_never_became_ready(self):
        now = [0]

        def opener(url, timeout):
            if "loki" in url:
                raise OSError("connection refused")
            return self.Response()

        def sleep(seconds):
            now[0] += seconds

        with self.assertRaisesRegex(apply.ApplyError, "Loki$"):
            apply.wait_until_ready({"Grafana": "http://grafana", "Loki": "http://loki"}, opener=opener,
                                   clock=lambda: now[0], sleep=sleep, timeout=30)


if __name__ == "__main__":
    unittest.main()
