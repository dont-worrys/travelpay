import base64
import importlib.util
from pathlib import Path
import subprocess
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

    def test_wallet_key_is_base64_of_32_random_bytes(self):
        # Wallet은 Base64로 풀어 정확히 32바이트인 AES-256 키만 받아들입니다.
        first = parameters.generate("/travelpay/production/WALLET_ACCOUNT_NUMBER_KEY")
        second = parameters.generate("/travelpay/production/WALLET_ACCOUNT_NUMBER_KEY")
        self.assertEqual(len(base64.b64decode(first, validate=True)), 32)
        self.assertNotEqual(first, second)

    def test_external_key_is_stored_only_when_requested_and_never_generated(self):
        calls = []

        def aws(operation, payload):
            calls.append((operation, payload))
            return {"Parameters": []} if operation == "describe-parameters" else {}

        name = "/travelpay/production/KOREAEXIM_API_KEY"
        self.assertEqual(parameters.prepare(aws, [name], apply=True), [(name, "needs-external-input")])
        self.assertEqual([operation for operation, _ in calls], ["describe-parameters"])
        parameters.prepare(aws, [name], apply=True, external_value=lambda requested: "issued-key-0123456789")
        self.assertEqual(calls[-1][1]["Value"], "issued-key-0123456789")
        self.assertFalse(calls[-1][1]["Overwrite"])

    def test_external_key_input_rejects_blank_or_spaced_values(self):
        for value in ["", "short", "has space inside", "line\nbreak-0123"]:
            with self.subTest(value=value), self.assertRaises(parameters.ParameterPreparationError):
                parameters.read_external("/travelpay/production/KOREAEXIM_API_KEY", prompt=lambda message: value)

    def test_contract_must_classify_every_secret(self):
        account = "123456789012"
        names = [f"{prefix}_DB_PASSWORD" for prefix in ["USER", "WALLET", "PAYMENT", "EXCHANGE", "LEDGER", "MOCK_BANK"]]
        names += ["REDIS_SESSION_PASSWORD", "REDIS_CACHE_PASSWORD", "WALLET_ACCOUNT_NUMBER_KEY", "KOREAEXIM_API_KEY"]
        contract = {name: f"arn:aws:ssm:ap-northeast-2:{account}:parameter/travelpay/production/{name}" for name in names}
        ordered = parameters.parameter_names(contract, account)
        self.assertEqual(len(ordered), 10)
        self.assertEqual(ordered[-1], "/travelpay/production/KOREAEXIM_API_KEY")
        missing_wallet_with_extra_database = {name: arn for name, arn in contract.items() if name != "WALLET_ACCOUNT_NUMBER_KEY"}
        missing_wallet_with_extra_database["AUDIT_DB_PASSWORD"] = f"arn:aws:ssm:ap-northeast-2:{account}:parameter/travelpay/production/AUDIT_DB_PASSWORD"
        for broken in [
            missing_wallet_with_extra_database,
            {**contract, "NEW_SERVICE_TOKEN": f"arn:aws:ssm:ap-northeast-2:{account}:parameter/travelpay/production/NEW_SERVICE_TOKEN"},
            {**contract, "USER_DB_PASSWORD": f"arn:aws:ssm:ap-northeast-2:999999999999:parameter/travelpay/production/USER_DB_PASSWORD"},
            {name: arn for name, arn in contract.items() if name != "KOREAEXIM_API_KEY"},
        ]:
            with self.subTest(names=sorted(broken)), self.assertRaises(parameters.ParameterPreparationError):
                parameters.parameter_names(broken, account)


    def test_wallet_key_is_created_only_with_explicit_flag(self):
        # 키가 지워진 뒤 무심코 다시 만들면 저장된 계좌번호를 풀 수 없으므로 명시한 경우에만 만듭니다.
        calls = []

        def aws(operation, payload):
            calls.append((operation, payload))
            return {"Parameters": []} if operation == "describe-parameters" else {}

        name = "/travelpay/production/WALLET_ACCOUNT_NUMBER_KEY"
        self.assertEqual(parameters.prepare(aws, [name], apply=True), [(name, "needs-create-wallet-key")])
        self.assertNotIn("put-parameter", [operation for operation, _ in calls])
        parameters.prepare(aws, [name], apply=True, create_wallet_key=True)
        self.assertEqual(calls[-1][0], "put-parameter")
        self.assertEqual(len(base64.b64decode(calls[-1][1]["Value"], validate=True)), 32)
        self.assertFalse(calls[-1][1]["Overwrite"])

    def test_external_prompt_refuses_when_input_cannot_be_hidden(self):
        # 제어 터미널 없이 파이프로 입력하면 getpass가 입력을 숨기지 못하므로 키를 받지 않아야 합니다.
        code = ("import importlib.util, sys\n"
                "spec = importlib.util.spec_from_file_location('tool', sys.argv[1])\n"
                "tool = importlib.util.module_from_spec(spec); spec.loader.exec_module(tool)\n"
                "try:\n    tool.read_external('/travelpay/production/KOREAEXIM_API_KEY')\n"
                "except tool.ParameterPreparationError:\n    sys.exit(3)\n"
                "sys.exit(0)\n")
        result = subprocess.run([sys.executable, "-c", code, str(path)], input="issued-key-0123456789\n",
                                capture_output=True, text=True, timeout=30, start_new_session=True)
        self.assertEqual(result.returncode, 3, result.stderr)
        self.assertNotIn("issued-key-0123456789", result.stdout + result.stderr)

    def test_progress_is_reported_before_a_later_failure(self):
        reported = []

        def aws(operation, payload):
            if operation == "describe-parameters":
                return {"Parameters": []}
            if payload["Name"].endswith("USER_DB_PASSWORD"):
                raise subprocess.CalledProcessError(254, "aws")
            return {}

        names = ["/travelpay/production/REDIS_CACHE_PASSWORD", "/travelpay/production/USER_DB_PASSWORD"]
        with self.assertRaises(subprocess.CalledProcessError):
            parameters.prepare(aws, names, apply=True, report=lambda name, status: reported.append((name, status)))
        self.assertEqual(reported, [("/travelpay/production/REDIS_CACHE_PASSWORD", "created")])

    def test_cli_history_setting_blocks_secret_writes(self):
        self.assertTrue(parameters.history_enabled(lambda arguments: "enabled\n"))
        self.assertFalse(parameters.history_enabled(lambda arguments: ""))
        self.assertFalse(parameters.history_enabled(lambda arguments: "disabled\n"))


if __name__ == "__main__":
    unittest.main()
