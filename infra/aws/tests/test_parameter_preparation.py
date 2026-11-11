import importlib.util
from pathlib import Path
import sys
import unittest

path = Path(__file__).parents[1] / "scripts" / "prepare-parameters.py"
spec = importlib.util.spec_from_file_location("parameter_preparation", path)
parameters = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parameters)


class ParameterPreparationTest(unittest.TestCase):
    def test_cli_reads_json_without_secret_arguments_or_persistent_file(self):
        script = "import json,pathlib,sys; print(json.dumps({'payload':json.loads(pathlib.Path(sys.argv[-1][7:]).read_text()),'arguments':sys.argv[1:]}))"
        payload = {"Value": "test-secret-value-123"}
        result = parameters.run_json([sys.executable, "-c", script], payload)
        self.assertEqual(result["payload"], payload)
        self.assertNotIn(payload["Value"], str(result["arguments"]))
        self.assertFalse(Path(result["arguments"][-1][7:]).exists())

    def test_existing_secure_parameter_is_never_overwritten(self):
        calls = []

        def aws(operation, payload):
            calls.append((operation, payload))
            return {"Parameters": [{"Type": "SecureString"}]}

        parameters.prepare(aws, ["/travelpay/production/USER_DB_PASSWORD"], apply=True)
        self.assertEqual([name for name, _ in calls], ["describe-parameters"])

    def test_preview_never_writes_and_apply_creates_without_overwrite(self):
        calls = []

        def aws(operation, payload):
            calls.append((operation, payload))
            return {"Parameters": []} if operation == "describe-parameters" else {}

        names = ["/travelpay/production/REDIS_SESSION_PASSWORD"]
        parameters.prepare(aws, names, apply=False)
        self.assertEqual(len(calls), 1)
        parameters.prepare(aws, names, apply=True)
        write = calls[-1]
        self.assertEqual(write[0], "put-parameter")
        self.assertFalse(write[1]["Overwrite"])
        self.assertEqual(write[1]["Type"], "SecureString")
        self.assertRegex(write[1]["Value"], r"^[a-f0-9]{64}$")


if __name__ == "__main__":
    unittest.main()
