"""이미지 게시 순서와 거부 조건을 가짜 명령 실행기로 검사합니다. 실제 Docker·AWS는 호출하지 않습니다."""

import importlib.util
from pathlib import Path
import subprocess
import unittest

path = Path(__file__).parents[1] / "scripts" / "publish-images.py"
spec = importlib.util.spec_from_file_location("publish_images", path)
publish = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publish)

REVISION = "0123456789abcdef0123456789abcdef01234567"
REGISTRY = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com"
REPOSITORIES = {"user": f"{REGISTRY}/travelpay/user-service", "mock_bank": f"{REGISTRY}/travelpay/mock-bank"}
DIGEST = "sha256:" + "a" * 64


class FakeAws:
    """describe-images 응답을 차례로 돌려줍니다."""

    def __init__(self, *responses):
        self.responses = list(responses)

    def __call__(self, arguments, check=True):
        returncode, stdout, stderr = self.responses.pop(0)
        return subprocess.CompletedProcess(["aws", *arguments], returncode, stdout, stderr)


class PublishImagesTest(unittest.TestCase):
    def test_uncommitted_changes_are_rejected(self):
        with self.assertRaises(publish.PublishError):
            publish.source_revision(lambda command: " M infra/aws/registry.tf\n")

    def test_revision_must_be_a_full_commit_sha(self):
        outputs = iter(["", "not-a-sha\n"])
        with self.assertRaises(publish.PublishError):
            publish.source_revision(lambda command: next(outputs))

    def test_repositories_map_to_modules_and_short_sha_tags(self):
        images = publish.plan_images(REPOSITORIES, REVISION, [])
        self.assertEqual([(i["key"], i["module"], i["tag"]) for i in images],
                         [("mock_bank", "mock-bank", "0123456789ab"), ("user", "user-service", "0123456789ab")])

    def test_unknown_service_empty_output_and_missing_module_are_rejected(self):
        for repositories, selected in [(REPOSITORIES, ["nope"]), ({}, []), ({"x": f"{REGISTRY}/travelpay/not-a-module"}, [])]:
            with self.subTest(repositories=repositories, selected=selected), self.assertRaises(publish.PublishError):
                publish.plan_images(repositories, REVISION, selected)

    def test_existing_tag_is_reused_without_building(self):
        commands, logins = [], []
        images = publish.plan_images(REPOSITORIES, REVISION, ["user"])
        result = publish.publish(images, lambda command, capture=True: commands.append(command), FakeAws((0, DIGEST + "\n", "")),
                                 logins.append)
        self.assertEqual(commands, [])
        self.assertEqual(logins, [REGISTRY])
        self.assertEqual(result, {"user": f"{REGISTRY}/travelpay/user-service@{DIGEST}"})

    def test_missing_tag_is_built_for_amd64_pushed_and_read_back(self):
        commands = []
        images = publish.plan_images(REPOSITORIES, REVISION, ["mock_bank"])
        aws = FakeAws((254, "", "An error occurred (ImageNotFoundException)"), (0, DIGEST, ""))
        result = publish.publish(images, lambda command, capture=True: commands.append(command), aws, lambda registry: None)
        build, push = commands
        self.assertEqual(build[:4], ["docker", "build", "--platform", "linux/amd64"])
        self.assertIn("SERVICE=mock-bank", build)
        self.assertEqual(push, ["docker", "push", f"{REGISTRY}/travelpay/mock-bank:0123456789ab"])
        self.assertEqual(result["mock_bank"], f"{REGISTRY}/travelpay/mock-bank@{DIGEST}")

    def test_lookup_errors_other_than_missing_tag_stop_publishing(self):
        images = publish.plan_images(REPOSITORIES, REVISION, ["user"])
        with self.assertRaises(publish.PublishError):
            publish.publish(images, lambda command, capture=True: None, FakeAws((255, "", "AccessDeniedException")),
                            lambda registry: None)


if __name__ == "__main__":
    unittest.main()
