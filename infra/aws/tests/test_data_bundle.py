"""설정 묶음의 해시 확인·설치·반영 순서를 가짜 명령 실행기로 검사합니다. 실제 AWS·systemd는 호출하지 않습니다."""

import gzip
import hashlib
import importlib.util
import json
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


fetch = load("fetch-data-bundle")
apply = load("apply-data-bundle")

COMPOSE_BINARY = b"compose-binary"
FILES = {
    "runtime.json": json.dumps({"region": "ap-northeast-2", "compose_version": "v5.7.0",
                                "compose_sha256": hashlib.sha256(COMPOSE_BINARY).hexdigest()}),
    "compose.yaml": "services: {}\n",
    "travelpay-data.service": "[Unit]\n",
    "cloudwatch-agent.json": "{}",
    "mount-data-volumes.py": "", "prepare-data.py": "", "bootstrap-database.py": "", "apply-data-bundle.py": "",
}


def bundle(files=FILES):
    document = json.dumps(files).encode()
    return gzip.compress(document), hashlib.sha256(document).hexdigest()


class Recorder:
    """명령을 기록하고 다운로드는 지정한 내용으로 파일을 만듭니다."""

    def __init__(self, downloads=None, failing=None):
        self.commands, self.downloads, self.failing = [], downloads or {}, failing

    def __call__(self, command, check=False, **_):
        self.commands.append(command)
        if command[0] == "curl":
            Path(command[command.index("-o") + 1]).write_bytes(self.downloads.get(command[-3], b"-----BEGIN CERTIFICATE-----"))
        if self.failing and self.failing in command and check:
            raise subprocess.CalledProcessError(1, command)
        return subprocess.CompletedProcess(command, 0, "", "")

    def ran(self, *words):
        return [command for command in self.commands if all(word in command for word in words)]


class FetchTest(unittest.TestCase):
    def test_rejects_bundle_whose_hash_differs_from_terraform(self):
        compressed, _ = bundle()
        with tempfile.TemporaryDirectory() as work, self.assertRaises(fetch.BundleError):
            fetch.unpack(compressed, "0" * 64, Path(work))

    def test_rejects_paths_outside_the_staging_directory(self):
        compressed, digest = bundle(dict(FILES, **{"../escape": "x"}))
        with tempfile.TemporaryDirectory() as work, self.assertRaises(fetch.BundleError):
            fetch.unpack(compressed, digest, Path(work))

    def test_rejects_malformed_request(self):
        for environ in [{}, {"TRAVELPAY_BUNDLE_BUCKET": "b", "TRAVELPAY_BUNDLE_KEY": "k", "TRAVELPAY_REGION": "r",
                             "TRAVELPAY_BUNDLE_SHA256": "not-a-hash"}]:
            with self.subTest(environ=environ), self.assertRaises(fetch.BundleError):
                fetch.read_request(environ)

    def test_downloads_verifies_and_runs_bundled_apply_script(self):
        compressed, digest = bundle()
        environ = {"TRAVELPAY_BUNDLE_BUCKET": "travelpay-production-data-config-123456789012",
                   "TRAVELPAY_BUNDLE_KEY": "data/bundle.json.gz", "TRAVELPAY_BUNDLE_SHA256": digest,
                   "TRAVELPAY_REGION": "ap-northeast-2"}
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

    def test_reports_the_aws_error_code_when_download_fails(self):
        _, digest = bundle()
        environ = {"TRAVELPAY_BUNDLE_BUCKET": "b", "TRAVELPAY_BUNDLE_KEY": "k", "TRAVELPAY_REGION": "r",
                   "TRAVELPAY_BUNDLE_SHA256": digest}
        denied = "An error occurred (AccessDenied) when calling the GetObject operation: Access Denied"

        def run(command, **_):
            failed = command[:3] == ["aws", "s3api", "get-object"]
            return subprocess.CompletedProcess(command, 255 if failed else 0, "", denied if failed else "")

        with self.assertRaisesRegex(fetch.BundleError, "AccessDenied"):
            fetch.main(environ, run, which=lambda tool: "/usr/bin/" + tool)

    def test_script_embedded_in_ssm_document_has_no_parameter_placeholders(self):
        # SSM은 문서의 이중 중괄호를 매개변수로 치환하므로 내장 스크립트에 들어가면 안 됩니다.
        script = (Path(__file__).parents[1] / "scripts" / "fetch-data-bundle.py").read_text()
        self.assertNotIn("{" * 2, script)
        self.assertNotIn("}" * 2, script)

    def test_stops_before_download_when_tools_are_missing(self):
        _, digest = bundle()
        environ = {"TRAVELPAY_BUNDLE_BUCKET": "b", "TRAVELPAY_BUNDLE_KEY": "k", "TRAVELPAY_REGION": "r",
                   "TRAVELPAY_BUNDLE_SHA256": digest}
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

    def tearDown(self):
        self.work.cleanup()

    def test_first_apply_installs_everything_and_starts_the_service(self):
        run = Recorder({self.compose_url: COMPOSE_BINARY})
        apply.apply(self.source, run, self.paths)
        self.assertEqual((self.paths.data / "compose.yaml").read_text(), FILES["compose.yaml"])
        self.assertEqual(oct((self.paths.data / "runtime.json").stat().st_mode & 0o777), "0o600")
        self.assertEqual(self.paths.compose.read_bytes(), COMPOSE_BINARY)
        self.assertEqual(len(run.ran("fetch-config")), 1)
        self.assertEqual(len(run.ran("daemon-reload")), 1)
        self.assertEqual(run.commands[-2:], [["systemctl", "reset-failed", "travelpay-data.service"],
                                             ["systemctl", "reload-or-restart", "travelpay-data.service"]])

    def test_retry_with_same_bundle_reapplies_host_steps_without_downloading(self):
        # 첫 실행이 파일 설치 뒤 반영 단계에서 실패해도, 같은 묶음의 재시도가 그 단계를 다시 실행해야 합니다.
        with self.assertRaises(subprocess.CalledProcessError):
            apply.apply(self.source, Recorder({self.compose_url: COMPOSE_BINARY}, failing="fetch-config"), self.paths)
        again = Recorder()
        apply.apply(self.source, again, self.paths)
        self.assertEqual(again.ran("curl"), [])
        self.assertEqual(len(again.ran("fetch-config")), 1)
        self.assertEqual(len(again.ran("daemon-reload")), 1)
        self.assertEqual(again.commands[-1], ["systemctl", "reload-or-restart", "travelpay-data.service"])

    def test_partial_certificate_is_downloaded_again(self):
        self.paths.data.mkdir(parents=True)
        (self.paths.data / "rds-ca.pem").write_bytes(b"truncated")
        run = Recorder({self.compose_url: COMPOSE_BINARY})
        apply.apply(self.source, run, self.paths)
        self.assertIn(b"BEGIN CERTIFICATE", (self.paths.data / "rds-ca.pem").read_bytes())

    def test_rejects_certificate_download_that_is_not_pem(self):
        url = "https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem"
        with self.assertRaises(apply.ApplyError):
            apply.apply(self.source, Recorder({self.compose_url: COMPOSE_BINARY, url: b"<html>error</html>"}), self.paths)
        self.assertFalse((self.paths.data / "rds-ca.pem").exists())

    def test_rejects_compose_binary_with_unexpected_hash(self):
        with self.assertRaises(apply.ApplyError):
            apply.apply(self.source, Recorder({self.compose_url: b"tampered"}), self.paths)
        self.assertFalse(self.paths.compose.exists())

    def test_failed_reload_is_reported_to_ssm(self):
        run = Recorder({self.compose_url: COMPOSE_BINARY}, failing="reload-or-restart")
        with self.assertRaises(subprocess.CalledProcessError):
            apply.apply(self.source, run, self.paths)


if __name__ == "__main__":
    unittest.main()
