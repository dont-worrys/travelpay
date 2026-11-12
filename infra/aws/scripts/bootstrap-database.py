#!/usr/bin/env python3
"""초기화 권한이 열린 Data EC2에서만 실행합니다. Flyway는 이후 앱 기동 때 실행합니다."""

import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import subprocess
import sys

SCRAM_ITERATIONS = 4096
# 이름을 바꾸기 전에 쓰던 앱 계정입니다. 새 계정이 스키마를 넘겨받은 뒤 지웁니다(2026-11-12 mock-bank 계정을 로컬 이름으로 맞춤).
RETIRED_ROLES = ("bank_app",)


class DatabaseBootstrapError(ValueError):
    pass


def failure_message(error):
    reason = str(error) if isinstance(error, DatabaseBootstrapError) else type(error).__name__
    return f"Database initialization failed: {reason}"


def scram_verifier(password, salt, iterations=SCRAM_ITERATIONS):
    """PostgreSQL이 저장하는 SCRAM-SHA-256 값입니다. 평문 대신 보내면 통계·로그에 암호 원문이 남지 않습니다."""
    salted = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iterations)
    client_key = hmac.new(salted, b"Client Key", hashlib.sha256).digest()
    server_key = hmac.new(salted, b"Server Key", hashlib.sha256).digest()

    def encode(value):
        return base64.b64encode(value).decode()

    return (f"SCRAM-SHA-256${iterations}:{encode(salt)}$"
            f"{encode(hashlib.sha256(client_key).digest())}:{encode(server_key)}")


def build_sql(name, databases, passwords, salt=lambda: os.urandom(16), retired=RETIRED_ROLES):
    def identifier(value):
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", value):
            raise DatabaseBootstrapError("DB 식별자 형식이 올바르지 않습니다.")
        return '"' + value + '"'

    db_name = identifier(name)
    retired_roles = {old: identifier(old) for old in retired}
    if set(retired_roles) & {service["username"] for service in databases.values()}:
        raise DatabaseBootstrapError("지울 계정이 현재 실행 계약에 있습니다.")
    lines = ["BEGIN;", f"REVOKE CONNECT ON DATABASE {db_name} FROM PUBLIC;", "REVOKE CREATE ON SCHEMA public FROM PUBLIC;"]
    for old, role in retired_roles.items():
        # PostgreSQL 16부터 계정을 만든 쪽은 ADMIN만 갖고 그 권한을 물려받지 않습니다. 이전 초기화가 남긴 권한이 없으면
        # 옛 계정 소유의 스키마를 넘길 수 없으므로(must be owner of schema) 잠시 받습니다. 옛 계정을 지우면 함께 사라집니다.
        # SQL의 AND는 왼쪽부터 평가된다는 보장이 없으므로, 계정이 없을 때 pg_has_role이 실패하지 않도록 따로 확인합니다.
        lines.append(f"""DO $retire_prepare$
DECLARE target_oid oid;
BEGIN
  SELECT oid INTO target_oid FROM pg_roles WHERE rolname = '{old}';
  IF target_oid IS NOT NULL THEN
    IF NOT pg_has_role(current_user, target_oid, 'USAGE') THEN
      GRANT {role} TO CURRENT_USER WITH INHERIT TRUE;
    END IF;
  END IF;
END $retire_prepare$;""")
    for key, service in databases.items():
        role, schema = identifier(service["username"]), identifier(service["schema"])
        password = passwords[key]
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", password):
            raise DatabaseBootstrapError("DB 비밀번호 형식이 올바르지 않습니다.")
        verifier = scram_verifier(password, salt())
        lines.extend([
            f"""DO $bootstrap$
DECLARE target_oid oid; could_set boolean; had_membership boolean;
BEGIN
  SELECT oid INTO target_oid FROM pg_roles WHERE rolname = '{service['username']}';
  IF target_oid IS NULL THEN
    CREATE ROLE {role} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    SELECT oid INTO target_oid FROM pg_roles WHERE rolname = '{service['username']}';
  ELSIF EXISTS (SELECT FROM pg_roles WHERE oid = target_oid AND
    (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
    OR EXISTS (SELECT FROM pg_auth_members WHERE member = target_oid) THEN
    RAISE EXCEPTION 'Existing application role has unexpected privileges';
  END IF;
  ALTER ROLE {role} WITH LOGIN PASSWORD '{verifier}';
  SELECT pg_has_role(current_user, target_oid, 'SET') INTO could_set;
  SELECT EXISTS (SELECT FROM pg_auth_members WHERE roleid = target_oid AND
    member = (SELECT oid FROM pg_roles WHERE rolname = current_user)) INTO had_membership;
  IF NOT could_set THEN
    GRANT {role} TO CURRENT_USER WITH SET TRUE;
  END IF;
  CREATE SCHEMA IF NOT EXISTS {schema} AUTHORIZATION {role};
  ALTER SCHEMA {schema} OWNER TO {role};
  REVOKE ALL ON SCHEMA {schema} FROM PUBLIC;
  IF NOT could_set THEN
    IF had_membership THEN
      REVOKE SET OPTION FOR {role} FROM CURRENT_USER;
    ELSE
      REVOKE {role} FROM CURRENT_USER;
    END IF;
  END IF;
END $bootstrap$;""",
            f"GRANT CONNECT ON DATABASE {db_name} TO {role};",
            f"ALTER ROLE {role} IN DATABASE {db_name} SET search_path TO {schema};",
        ])
    for old, role in retired_roles.items():
        # 데이터를 자동으로 지우지 않도록 DROP OWNED는 쓰지 않습니다. 옛 계정에 객체가 남아 있으면 전체를 되돌립니다.
        lines.append(f"""DO $retire$
DECLARE target_oid oid;
BEGIN
  SELECT oid INTO target_oid FROM pg_roles WHERE rolname = '{old}';
  IF target_oid IS NULL THEN
    RETURN;
  END IF;
  IF EXISTS (SELECT FROM pg_roles WHERE oid = target_oid AND
      (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
    OR EXISTS (SELECT FROM pg_namespace WHERE nspowner = target_oid)
    OR EXISTS (SELECT FROM pg_class WHERE relowner = target_oid) THEN
    RAISE EXCEPTION 'Retired application role still has privileges or objects';
  END IF;
  REVOKE ALL ON DATABASE {db_name} FROM {role};
  DROP ROLE {role};
END $retire$;""")
    return "\n".join(lines) + "\nCOMMIT;\n"


def verify_logins(login, databases, passwords):
    # 초기화 SQL의 성공만으로는 SSM 암호와 DB 암호가 같은지 모릅니다. 앱이 쓰는 계정·암호로 직접 로그인해 확인합니다.
    for key, service in databases.items():
        result = login(service["username"], passwords[key], "SELECT current_user || '|' || current_schema();")
        if result.returncode or result.stdout.strip() != f"{service['username']}|{service['schema']}":
            raise DatabaseBootstrapError(f"{service['username']} 계정이 SSM 암호로 로그인해 자기 스키마를 쓰지 못합니다.")


def psql_errors(stderr):
    # 원인을 알 수 있도록 ERROR·DETAIL·HINT 줄만 보여 줍니다. 실행한 문장이 담긴 CONTEXT 줄은 빼고, 혹시 섞인 SCRAM 값은 가립니다.
    lines = [line.strip() for line in stderr.splitlines() if line.startswith(("ERROR:", "DETAIL:", "HINT:"))]
    return re.sub(r"SCRAM-SHA-256\$[^'\s]*", "SCRAM-SHA-256$***", " / ".join(lines))[:500] or "psql 오류 내용 없음"


def reset_statement_stats(psql):
    # 암호를 바꾼 뒤 옛 평문 암호가 남았을 수 있는 쿼리 통계를 지웁니다. 통계 수집은 미리 불러온 라이브러리가 하지만
    # 비우는 함수는 확장을 만들어야 생기므로 없으면 만듭니다(통계 조회·비우기 함수만 추가). 옛 암호는 교체 뒤 무효입니다.
    result = psql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements;\nSELECT pg_stat_statements_reset();")
    if result.returncode:
        raise DatabaseBootstrapError(f"쿼리 통계를 비우지 못했습니다({psql_errors(result.stderr)}). "
                                     "계정·암호 변경은 이미 반영됐습니다.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reset-statement-stats", action="store_true",
                        help="초기화 뒤 pg_stat_statements 통계를 비웁니다. DB 암호를 교체한 작업 창에서 씁니다.")
    args = parser.parse_args()
    config = json.loads((Path(__file__).resolve().parent / "runtime.json").read_text())

    def aws(*arguments):
        result = subprocess.check_output(["aws", *arguments, "--region", config["region"], "--output", "json"],
                                         text=True, stderr=subprocess.PIPE, timeout=30)
        return json.loads(result)

    master = json.loads(aws("secretsmanager", "get-secret-value", "--secret-id", config["master_secret_arn"])["SecretString"])
    passwords = {
        key: aws("ssm", "get-parameter", "--name", config["parameter_prefix"] + "/" + service["password_parameter"],
                 "--with-decryption")["Parameter"]["Value"]
        for key, service in config["databases"].items()
    }
    # psql에는 암호를 명령 인자가 아닌 해당 자식 프로세스의 환경으로 전달합니다.
    env = dict(os.environ, PGPASSWORD=master["password"], PGSSLMODE="verify-full",
               PGSSLROOTCERT="/opt/travelpay/data/rds-ca.pem", PGCONNECT_TIMEOUT="10")

    def psql(statement):
        return subprocess.run([
            "psql", "-X", "--no-password", "--set=ON_ERROR_STOP=1", "--host", config["database_host"],
            "--username", master["username"], "--dbname", config["database_name"],
        ], input=statement, env=env, capture_output=True, text=True, timeout=120)

    initialized = psql(build_sql(config["database_name"], config["databases"], passwords))
    if initialized.returncode:
        # 한 트랜잭션이라 실패하면 계정·암호 변경 전체가 되돌아갑니다.
        raise DatabaseBootstrapError(f"DB 초기화가 되돌아갔습니다({psql_errors(initialized.stderr)}). "
                                     "원인을 해결한 뒤 재실행하세요.")

    def login(username, password, statement):
        # 앱 계정 암호도 명령 인자가 아니라 해당 psql 프로세스의 환경으로만 전달합니다.
        return subprocess.run([
            "psql", "-X", "--no-password", "--set=ON_ERROR_STOP=1", "--tuples-only", "--no-align",
            "--host", config["database_host"], "--username", username, "--dbname", config["database_name"],
        ], input=statement, env=dict(env, PGPASSWORD=password), capture_output=True, text=True, timeout=60)

    verify_logins(login, config["databases"], passwords)
    print(f"Database roles and schemas initialized; {len(passwords)} application accounts logged in with their SSM passwords. "
          "Disable enable_database_bootstrap_access after verification.")
    if args.reset_statement_stats:
        reset_statement_stats(psql)
        print("Statement statistics reset.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(failure_message(error), file=sys.stderr)
        sys.exit(1)
