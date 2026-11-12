import base64
import hashlib
import hmac
import importlib.util
from pathlib import Path
import subprocess
import unittest

path = Path(__file__).parents[1] / "scripts" / "bootstrap-database.py"
spec = importlib.util.spec_from_file_location("database_bootstrap", path)
database = importlib.util.module_from_spec(spec)
spec.loader.exec_module(database)


class DatabaseBootstrapTest(unittest.TestCase):
    def test_initialization_is_atomic_and_roles_are_not_administrators(self):
        sql = database.build_sql("travelpay", {"user": {"schema": "user_service", "username": "user_app"}}, {"user": "a" * 64})
        self.assertTrue(sql.startswith("BEGIN;\n"))
        self.assertTrue(sql.endswith("COMMIT;\n"))
        self.assertIn('CREATE ROLE "user_app" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;', sql)
        self.assertIn('CREATE SCHEMA IF NOT EXISTS "user_service" AUTHORIZATION "user_app";', sql)

    def test_scram_verifier_matches_rfc_7677_example(self):
        # RFC 7677 5절의 예시 교환입니다. 서버 서명과 클라이언트 증명이 같은 키에서 나와야 합니다.
        verifier = database.scram_verifier("pencil", base64.b64decode("W22ZaJ0SNY7soEsUEjb6gQ=="))
        prefix, salt_part, keys = verifier.split("$")
        stored_key, server_key = (base64.b64decode(value) for value in keys.split(":"))
        self.assertEqual((prefix, salt_part), ("SCRAM-SHA-256", "4096:W22ZaJ0SNY7soEsUEjb6gQ=="))
        auth_message = ("n=user,r=rOprNGfwEbeRWgbNEkqO,"
                        "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096,"
                        "c=biws,r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0").encode()
        server_signature = hmac.new(server_key, auth_message, hashlib.sha256).digest()
        self.assertEqual(base64.b64encode(server_signature).decode(), "6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4=")
        client_signature = hmac.new(stored_key, auth_message, hashlib.sha256).digest()
        proof = base64.b64decode("dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=")
        client_key = bytes(a ^ b for a, b in zip(proof, client_signature))
        self.assertEqual(hashlib.sha256(client_key).digest(), stored_key)

    def test_sql_carries_only_the_verifier_not_the_password(self):
        password = "p" * 64
        sql = database.build_sql("travelpay", {"user": {"schema": "user_service", "username": "user_app"}},
                                 {"user": password}, salt=lambda: b"\x00" * 16)
        self.assertNotIn(password, sql)
        self.assertIn("PASSWORD '" + database.scram_verifier(password, b"\x00" * 16) + "'", sql)

    def test_retired_role_is_dropped_only_after_the_new_role_takes_over_its_schema(self):
        sql = database.build_sql("travelpay", {"mock_bank": {"schema": "mock_bank", "username": "mock_bank_app"}},
                                 {"mock_bank": "a" * 64}, retired=("bank_app",))
        self.assertLess(sql.index('ALTER SCHEMA "mock_bank" OWNER TO "mock_bank_app";'), sql.index('DROP ROLE "bank_app";'))
        self.assertLess(sql.index('DROP ROLE "bank_app";'), sql.index("COMMIT;"))
        self.assertNotIn("DROP OWNED", sql)

    def test_a_role_in_the_contract_cannot_be_retired(self):
        with self.assertRaises(ValueError):
            database.build_sql("travelpay", {"mock_bank": {"schema": "mock_bank", "username": "bank_app"}},
                               {"mock_bank": "a" * 64}, retired=("bank_app",))

    def test_statement_stats_reset_reports_a_refused_reset_clearly(self):
        statements = []

        def refused(statement):
            return subprocess.CompletedProcess(["psql"], 1, "", "ERROR:  permission denied for function pg_stat_statements_reset\n")

        with self.assertRaisesRegex(ValueError, "permission denied for function pg_stat_statements_reset.*이미 반영"):
            database.reset_statement_stats(lambda statement: (statements.append(statement), refused(statement))[1])
        self.assertEqual(statements[0], "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;\nSELECT pg_stat_statements_reset();")
        database.reset_statement_stats(lambda statement: subprocess.CompletedProcess(["psql"], 0, "", ""))

    def test_psql_errors_keep_the_cause_but_not_the_statement_or_verifier(self):
        stderr = ("NOTICE:  schema \"mock_bank\" already exists, skipping\n"
                  "ERROR:  role \"bank_app\" cannot be dropped because some objects depend on it\n"
                  "DETAIL:  privileges for database postgres\n"
                  "CONTEXT:  SQL statement \"ALTER ROLE \"user_app\" WITH LOGIN PASSWORD 'SCRAM-SHA-256$4096:abc$def:ghi'\"\n"
                  "ERROR:  near 'SCRAM-SHA-256$4096:zzz$yyy:xxx'\n")
        message = database.psql_errors(stderr)
        self.assertIn("cannot be dropped", message)
        self.assertIn("privileges for database postgres", message)
        self.assertNotIn("CONTEXT", message)
        self.assertNotIn("ALTER ROLE", message)
        self.assertNotIn("4096:zzz", message)
        self.assertEqual(database.psql_errors(""), "psql 오류 내용 없음")

    def test_each_application_account_logs_in_with_its_password_and_lands_in_its_schema(self):
        databases = {"user": {"schema": "user_service", "username": "user_app"},
                     "ledger": {"schema": "ledger_service", "username": "ledger_app"}}
        logins = []

        def login(username, password, statement):
            logins.append((username, password, statement))
            return subprocess.CompletedProcess(["psql"], 0, f"{username}|{databases['user' if username == 'user_app' else 'ledger']['schema']}\n", "")

        database.verify_logins(login, databases, {"user": "a" * 64, "ledger": "b" * 64})

        self.assertEqual([(username, password) for username, password, _ in logins],
                         [("user_app", "a" * 64), ("ledger_app", "b" * 64)])

    def test_a_failed_or_misplaced_login_names_only_the_account(self):
        databases = {"user": {"schema": "user_service", "username": "user_app"}}
        cases = [subprocess.CompletedProcess(["psql"], 2, "", "password authentication failed"),
                 subprocess.CompletedProcess(["psql"], 0, "user_app|public\n", "")]
        for result in cases:
            with self.subTest(result=result), self.assertRaisesRegex(ValueError, "user_app") as raised:
                database.verify_logins(lambda username, password, statement: result, databases, {"user": "a" * 64})
            self.assertNotIn("a" * 64, str(raised.exception))

    def test_identifiers_and_passwords_cannot_inject_sql(self):
        for username, password in [("user_app; DROP DATABASE travelpay", "a" * 64), ("user_app", "a' OR true;--")]:
            with self.subTest(username=username), self.assertRaises(ValueError):
                database.build_sql("travelpay", {"user": {"schema": "user_service", "username": username}}, {"user": password})


if __name__ == "__main__":
    unittest.main()
