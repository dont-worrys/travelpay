"""자체 검증 실패는 설명하되 외부 오류의 비밀값·SQL은 로그에 내보내지 않습니다."""

import importlib.util
from pathlib import Path
import unittest


def load(name):
    path = Path(__file__).parents[1] / "scripts" / (name + ".py")
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class RuntimeErrorMessageTest(unittest.TestCase):
    def test_owned_validation_error_keeps_its_safe_reason(self):
        for name, kind in [("mount-data-volumes", "StorageError"), ("prepare-data", "PreparationError"),
                           ("prepare-monitoring", "PreparationError"),
                           ("bootstrap-database", "DatabaseBootstrapError"), ("prepare-parameters", "ParameterPreparationError"),
                           ("fetch-config-bundle", "BundleError"), ("apply-data-bundle", "ApplyError"),
                           ("apply-app-bundle", "ApplyError"), ("publish-images", "PublishError")]:
            module = load(name)
            with self.subTest(script=name):
                self.assertIn("지정한 데이터 경로가 일치하지 않습니다.", module.failure_message(getattr(module, kind)("지정한 데이터 경로가 일치하지 않습니다.")))

    def test_external_error_does_not_expose_response_or_password(self):
        for name in ["mount-data-volumes", "prepare-data", "prepare-monitoring", "bootstrap-database", "prepare-parameters",
                     "fetch-config-bundle",
                     "apply-data-bundle", "apply-app-bundle", "publish-images"]:
            module = load(name)
            with self.subTest(script=name):
                message = module.failure_message(RuntimeError("raw SQL password=private-test-value"))
                self.assertNotIn("private-test-value", message)
                self.assertNotIn("raw SQL", message)
                self.assertIn("RuntimeError", message)


if __name__ == "__main__":
    unittest.main()
