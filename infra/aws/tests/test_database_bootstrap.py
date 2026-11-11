import base64
import hashlib
import hmac
import importlib.util
from pathlib import Path
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

    def test_identifiers_and_passwords_cannot_inject_sql(self):
        for username, password in [("user_app; DROP DATABASE travelpay", "a" * 64), ("user_app", "a' OR true;--")]:
            with self.subTest(username=username), self.assertRaises(ValueError):
                database.build_sql("travelpay", {"user": {"schema": "user_service", "username": username}}, {"user": password})


if __name__ == "__main__":
    unittest.main()
