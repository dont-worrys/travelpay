"""선택 실행: TRAVELPAY_TEST_POSTGRES=1. 로컬의 고정 PostgreSQL 이미지로 SQL 권한 경계를 검사합니다."""

import contextlib
import importlib.util
import os
from pathlib import Path
import subprocess
import time
import unittest
import uuid

path = Path(__file__).parents[1] / "scripts" / "bootstrap-database.py"
spec = importlib.util.spec_from_file_location("bootstrap_sql", path)
database = importlib.util.module_from_spec(spec)
spec.loader.exec_module(database)


@contextlib.contextmanager
def postgres(test):
    """RDS처럼 superuser가 아닌 초기화 계정(bootstrap_admin)과 TCP SCRAM 인증을 갖춘 임시 PostgreSQL입니다."""
    name = "travelpay-245-db-check-" + uuid.uuid4().hex[:8]
    # 호스트 포트·볼륨·외부 네트워크 없이 테스트 컨테이너만 사용합니다.
    subprocess.run(["docker", "run", "--detach", "--rm", "--pull=never", "--name", name,
                    "--network", "none", "--memory", "512m", "--cpus", "1",
                    "--tmpfs", "/var/lib/postgresql:rw,size=512m",
                    "--env", "POSTGRES_HOST_AUTH_METHOD=trust", "--env", "POSTGRES_DB=travelpay",
                    "postgres:18.6-bookworm", "postgres", "-c", "shared_preload_libraries=pg_stat_statements"],
                   check=True, capture_output=True)
    try:
        def sql(statement, user="postgres"):
            return subprocess.run(["docker", "exec", "--interactive", name, "psql", "-X", "--no-password",
                                   "--set=ON_ERROR_STOP=1", "--tuples-only", "--no-align", "-U", user,
                                   "-d", "travelpay"], input=statement, capture_output=True, text=True, timeout=30)

        def login(user, password, statement="SELECT 1;"):
            # TCP로 접속하므로 SCRAM 암호 인증을 거칩니다. 실행 스크립트의 로그인 확인과 같은 형태로 결과를 받습니다.
            return subprocess.run(["docker", "exec", "--interactive", "--env", "PGPASSWORD=" + password, name, "psql", "-X",
                                   "--no-password", "--set=ON_ERROR_STOP=1", "--tuples-only", "--no-align",
                                   "-h", "127.0.0.1", "-U", user, "-d", "travelpay"],
                                  input=statement, capture_output=True, text=True, timeout=30)

        for _ in range(60):
            if sql("SELECT 1;").returncode == 0:
                break
            time.sleep(0.5)
        else:
            test.fail("PostgreSQL 테스트 컨테이너가 준비되지 않았습니다.")
        test.assertEqual(sql("CREATE ROLE bootstrap_admin LOGIN CREATEROLE CREATEDB; ALTER DATABASE travelpay OWNER TO bootstrap_admin;").returncode, 0)
        test.assertEqual(sql("CREATE EXTENSION pg_stat_statements;").returncode, 0)
        # 소켓은 그대로 두고 TCP 접속만 SCRAM 암호 인증을 요구합니다.
        hba = subprocess.run(["docker", "exec", name, "sh", "-c",
                              'printf "local all all trust\\nhost all all 127.0.0.1/32 scram-sha-256\\n" > "$PGDATA/pg_hba.conf"'],
                             capture_output=True, timeout=30)
        test.assertEqual(hba.returncode, 0, hba.stderr)
        test.assertEqual(sql("SELECT pg_reload_conf();").returncode, 0)
        yield sql, login
    finally:
        subprocess.run(["docker", "rm", "--force", name], capture_output=True, timeout=30)


@unittest.skipUnless(os.environ.get("TRAVELPAY_TEST_POSTGRES") == "1", "PostgreSQL 경계 검사는 명시적으로 실행합니다.")
class DatabaseBootstrapPostgresTest(unittest.TestCase):
    def test_non_superuser_initialization_rerun_and_schema_isolation(self):
        with postgres(self) as (sql, login):
            databases = {"user": {"schema": "user_service", "username": "user_app"},
                         "wallet": {"schema": "wallet_service", "username": "wallet_app"}}
            command = database.build_sql("travelpay", databases, {"user": "a" * 64, "wallet": "b" * 64})
            first = sql(command, "bootstrap_admin")
            self.assertEqual(first.returncode, 0, first.stderr)
            self.assertEqual(login("user_app", "a" * 64).returncode, 0)
            self.assertNotEqual(login("user_app", "c" * 64).returncode, 0)
            self.assertEqual(sql("SELECT count(*) FROM pg_stat_statements WHERE query LIKE '%" + "a" * 64 + "%';").stdout.strip(), "0")
            self.assertNotEqual(sql("SELECT count(*) FROM pg_stat_statements WHERE query LIKE '%SCRAM-SHA-256$%';").stdout.strip(), "0")
            self.assertEqual(sql("CREATE TABLE user_service.sentinel(id int); INSERT INTO user_service.sentinel VALUES(7);", "user_app").returncode, 0)
            again = sql(command, "bootstrap_admin")
            self.assertEqual(again.returncode, 0, again.stderr)
            self.assertEqual(sql("SELECT id FROM user_service.sentinel;", "user_app").stdout.strip(), "7")
            self.assertNotEqual(sql("SELECT * FROM user_service.sentinel;", "wallet_app").returncode, 0)
            self.assertEqual(sql("SELECT rolname FROM pg_roles WHERE rolname IN ('user_app','wallet_app') AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls);").stdout.strip(), "")
            self.assertEqual(sql("SELECT pg_has_role('bootstrap_admin','user_app','SET');").stdout.strip(), "f")
            self.assertEqual(sql("GRANT user_app TO bootstrap_admin WITH SET TRUE;").returncode, 0)
            preserved = sql(command, "bootstrap_admin")
            self.assertEqual(preserved.returncode, 0, preserved.stderr)
            self.assertEqual(sql("SELECT pg_has_role('bootstrap_admin','user_app','SET');").stdout.strip(), "t")
            self.assertEqual(sql("ALTER ROLE user_app SUPERUSER;").returncode, 0)
            self.assertNotEqual(sql(command, "bootstrap_admin").returncode, 0)

    def test_renamed_role_takes_over_the_schema_and_rotated_password_replaces_the_old_one(self):
        with postgres(self) as (sql, login):
            old = database.build_sql("travelpay", {"mock_bank": {"schema": "mock_bank", "username": "bank_app"}},
                                     {"mock_bank": "a" * 64}, retired=())
            self.assertEqual(sql(old, "bootstrap_admin").returncode, 0)
            renamed = database.build_sql("travelpay", {"mock_bank": {"schema": "mock_bank", "username": "mock_bank_app"}},
                                         {"mock_bank": "b" * 64}, retired=("bank_app",))

            applied = sql(renamed, "bootstrap_admin")

            self.assertEqual(applied.returncode, 0, applied.stderr)
            self.assertEqual(sql("SELECT count(*) FROM pg_roles WHERE rolname = 'bank_app';").stdout.strip(), "0")
            self.assertEqual(sql("SELECT nspowner::regrole FROM pg_namespace WHERE nspname = 'mock_bank';").stdout.strip(), "mock_bank_app")
            self.assertEqual(login("mock_bank_app", "b" * 64).returncode, 0)
            self.assertNotEqual(login("mock_bank_app", "a" * 64).returncode, 0)
            renamed_databases = {"mock_bank": {"schema": "mock_bank", "username": "mock_bank_app"}}
            database.verify_logins(login, renamed_databases, {"mock_bank": "b" * 64})
            with self.assertRaises(database.DatabaseBootstrapError):
                database.verify_logins(login, renamed_databases, {"mock_bank": "a" * 64})
            self.assertEqual(sql("CREATE TABLE mock_bank.sentinel(id int);", "mock_bank_app").returncode, 0)
            rerun = sql(renamed, "bootstrap_admin")
            self.assertEqual(rerun.returncode, 0, rerun.stderr)

    def test_rename_works_even_when_no_privileges_of_the_old_role_remain(self):
        with postgres(self) as (sql, login):
            old = database.build_sql("travelpay", {"mock_bank": {"schema": "mock_bank", "username": "bank_app"}},
                                     {"mock_bank": "a" * 64}, retired=())
            self.assertEqual(sql(old, "bootstrap_admin").returncode, 0)
            # 이전 초기화가 남긴 권한을 거둬, 만든 계정에 ADMIN만 남은 상태(PostgreSQL 16 이후 기본)를 만듭니다.
            self.assertEqual(sql("REVOKE bank_app FROM bootstrap_admin GRANTED BY bootstrap_admin;").returncode, 0)
            self.assertEqual(sql("SELECT pg_has_role('bootstrap_admin', 'bank_app', 'USAGE');").stdout.strip(), "f")
            renamed = database.build_sql("travelpay", {"mock_bank": {"schema": "mock_bank", "username": "mock_bank_app"}},
                                         {"mock_bank": "b" * 64}, retired=("bank_app",))

            applied = sql(renamed, "bootstrap_admin")

            self.assertEqual(applied.returncode, 0, applied.stderr)
            self.assertEqual(sql("SELECT nspowner::regrole FROM pg_namespace WHERE nspname = 'mock_bank';").stdout.strip(), "mock_bank_app")
            self.assertEqual(sql("SELECT count(*) FROM pg_roles WHERE rolname = 'bank_app';").stdout.strip(), "0")

    def test_a_retired_role_that_still_owns_objects_stops_the_whole_change(self):
        with postgres(self) as (sql, login):
            old = database.build_sql("travelpay", {"mock_bank": {"schema": "mock_bank", "username": "bank_app"}},
                                     {"mock_bank": "a" * 64}, retired=())
            self.assertEqual(sql(old, "bootstrap_admin").returncode, 0)
            self.assertEqual(sql("CREATE TABLE mock_bank.accounts(id int);", "bank_app").returncode, 0)
            renamed = database.build_sql("travelpay", {"mock_bank": {"schema": "mock_bank", "username": "mock_bank_app"}},
                                         {"mock_bank": "b" * 64}, retired=("bank_app",))

            refused = sql(renamed, "bootstrap_admin")

            self.assertNotEqual(refused.returncode, 0)
            self.assertIn("Retired application role still has privileges or objects", refused.stderr)
            self.assertEqual(sql("SELECT count(*) FROM pg_roles WHERE rolname = 'mock_bank_app';").stdout.strip(), "0")
            self.assertEqual(sql("SELECT nspowner::regrole FROM pg_namespace WHERE nspname = 'mock_bank';").stdout.strip(), "bank_app")
            self.assertEqual(login("bank_app", "a" * 64).returncode, 0)


    def test_statement_stats_reset_creates_the_missing_extension_and_reports_a_refusal(self):
        with postgres(self) as (sql, login):
            self.assertEqual(sql("DROP EXTENSION pg_stat_statements;").returncode, 0)
            # RDS 관리자처럼 superuser가 아니면 거절되고, 그 원인이 메시지에 드러나야 합니다.
            with self.assertRaisesRegex(database.DatabaseBootstrapError, r"permission denied.*\)\. 계정·암호 변경은 이미 반영"):
                database.reset_statement_stats(lambda statement: sql(statement, "bootstrap_admin"))
            database.reset_statement_stats(lambda statement: sql(statement, "postgres"))
            self.assertEqual(sql("SELECT count(*) FROM pg_extension WHERE extname = 'pg_stat_statements';").stdout.strip(), "1")

if __name__ == "__main__":
    unittest.main()
