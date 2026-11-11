"""선택 실행: TRAVELPAY_TEST_DOCKER_IMAGE=1. 실제 이미지를 빌드해 실행 사용자·권한·시작 스크립트를 검사합니다.

실행 이미지(Ubuntu 26.04)에서만 드러나는 결함(env 인터프리터 거부, ADD --chmod가 만든 폴더 권한)을 잡기 위한 검사입니다.
"""

import os
from pathlib import Path
import re
import subprocess
import unittest
import uuid

ROOT = Path(__file__).parents[3]
DOCKERFILE = ROOT / "deploy" / "docker" / "Dockerfile"


@unittest.skipUnless(os.environ.get("TRAVELPAY_TEST_DOCKER_IMAGE") == "1", "이미지 검사는 명시적으로 실행합니다.")
class ContainerImageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.image = "travelpay-image-check:" + uuid.uuid4().hex[:8]
        subprocess.run(["docker", "build", "--quiet", "--platform", "linux/amd64", "-f", str(DOCKERFILE),
                        "--build-arg", "SERVICE=mock-bank", "-t", cls.image, str(ROOT)], check=True, capture_output=True, timeout=1200)

    @classmethod
    def tearDownClass(cls):
        subprocess.run(["docker", "rmi", "-f", cls.image], capture_output=True, timeout=60)

    def run_in_image(self, *command):
        return subprocess.run(["docker", "run", "--rm", "--network", "none", self.image, *command],
                              capture_output=True, text=True, timeout=60)

    def test_entrypoint_runs_given_command_as_non_root(self):
        result = self.run_in_image("sh", "-c", "id -u")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "10001")

    def test_application_files_are_readable_but_not_writable(self):
        result = self.run_in_image("sh", "-c", "stat -c '%a %U' /opt/travelpay /opt/travelpay/app.jar "
                                   "/opt/travelpay/rds-ca.pem /opt/travelpay/entrypoint.sh")
        self.assertEqual(result.stdout.split("\n")[:4], ["755 root", "444 root", "444 root", "555 root"])

    def test_rds_ca_matches_the_pinned_checksum(self):
        pinned = re.search(r"--checksum=sha256:([0-9a-f]{64})", DOCKERFILE.read_text()).group(1)
        result = self.run_in_image("sha256sum", "/opt/travelpay/rds-ca.pem")
        self.assertEqual(result.stdout.split()[0], pinned)


if __name__ == "__main__":
    unittest.main()
