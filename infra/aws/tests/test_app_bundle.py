"""앱 호스트 Alloy 설치·재시작 순서를 가짜 명령 실행기로 검사합니다. 실제 systemd·Docker는 호출하지 않습니다."""

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

path = Path(__file__).parents[1] / "scripts" / "apply-app-bundle.py"
spec = importlib.util.spec_from_file_location("apply_app_bundle", path)
app = importlib.util.module_from_spec(spec)
spec.loader.exec_module(app)

FILES = {"config.alloy": "// alloy\n", "travelpay-alloy.service": "[Unit]\n", "apply-app-bundle.py": ""}


class AppBundleTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        root = Path(self.work.name)
        self.source = root / "bundle"
        self.source.mkdir()
        for name, contents in FILES.items():
            (self.source / name).write_text(contents)
        self.paths = app.Paths(root=root / "host")
        self.commands, self.waited = [], []

    def tearDown(self):
        self.work.cleanup()

    def run_command(self, command, check=False, **_):
        self.commands.append(command)
        return subprocess.CompletedProcess(command, 0, "", "")

    def apply(self):
        self.commands.clear()
        app.apply(self.source, self.run_command, self.paths, wait=self.waited.append)

    def test_first_apply_installs_files_and_restarts_the_collector(self):
        self.apply()
        self.assertEqual(self.paths.config.read_text(), FILES["config.alloy"])
        self.assertEqual(oct(self.paths.config.stat().st_mode & 0o777), "0o644")
        self.assertEqual(oct(self.paths.storage.stat().st_mode & 0o777), "0o700")
        self.assertEqual(self.commands[-1], ["systemctl", "restart", "travelpay-alloy.service"])
        self.assertEqual(self.waited, [app.READY_URL])

    def test_same_bundle_only_makes_sure_the_collector_is_running(self):
        # SSM이 새 호스트나 재실행으로 같은 묶음을 다시 적용해도 수집을 끊지 않습니다.
        self.apply()
        self.apply()
        self.assertEqual(self.commands[-1], ["systemctl", "start", "travelpay-alloy.service"])
        self.assertEqual(len(self.waited), 2)

    def test_changed_config_restarts_the_collector(self):
        self.apply()
        (self.source / "config.alloy").write_text("// changed\n")
        self.apply()
        self.assertEqual(self.commands[-1], ["systemctl", "restart", "travelpay-alloy.service"])

    def test_retry_after_failing_before_restart_still_restarts(self):
        # 새 설정을 설치한 뒤 다시 시작하기 전에 실패해도, 같은 묶음의 재시도가 컨테이너를 다시 만들어야 새 설정을 읽습니다.
        self.apply()
        (self.source / "config.alloy").write_text("// changed\n")

        def fail_on_reload(command, check=False, **_):
            if command == ["systemctl", "daemon-reload"]:
                raise subprocess.CalledProcessError(1, command)
            return self.run_command(command)

        with self.assertRaises(subprocess.CalledProcessError):
            app.apply(self.source, fail_on_reload, self.paths, wait=self.waited.append)
        self.apply()
        self.assertEqual(self.commands[-1], ["systemctl", "restart", "travelpay-alloy.service"])

    def test_collector_that_never_answers_fails_the_apply(self):
        now = [0]

        def opener(url, timeout):
            raise OSError("connection refused")

        def sleep(seconds):
            now[0] += seconds

        with self.assertRaises(app.ApplyError):
            app.wait_until_ready(app.READY_URL, opener=opener, clock=lambda: now[0], sleep=sleep, timeout=30)


if __name__ == "__main__":
    unittest.main()
