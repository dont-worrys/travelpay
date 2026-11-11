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

    def test_identifiers_and_passwords_cannot_inject_sql(self):
        for username, password in [("user_app; DROP DATABASE travelpay", "a" * 64), ("user_app", "a' OR true;--")]:
            with self.subTest(username=username), self.assertRaises(ValueError):
                database.build_sql("travelpay", {"user": {"schema": "user_service", "username": username}}, {"user": password})


if __name__ == "__main__":
    unittest.main()
