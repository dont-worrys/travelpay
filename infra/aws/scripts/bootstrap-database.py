#!/usr/bin/env python3
"""초기화 권한이 열린 Data EC2에서만 실행합니다. Flyway는 이후 앱 기동 때 실행합니다."""

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


def build_sql(name, databases, passwords, salt=lambda: os.urandom(16)):
    def identifier(value):
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", value):
            raise DatabaseBootstrapError("DB 식별자 형식이 올바르지 않습니다.")
        return '"' + value + '"'

    db_name = identifier(name)
    lines = ["BEGIN;", f"REVOKE CONNECT ON DATABASE {db_name} FROM PUBLIC;", "REVOKE CREATE ON SCHEMA public FROM PUBLIC;"]
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
    return "\n".join(lines) + "\nCOMMIT;\n"


def main():
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
    result = subprocess.run([
        "psql", "-X", "--no-password", "--set=ON_ERROR_STOP=1", "--host", config["database_host"],
        "--username", master["username"], "--dbname", config["database_name"],
    ], input=build_sql(config["database_name"], config["databases"], passwords), env=env,
       capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise DatabaseBootstrapError("DB 초기화 완료 여부를 확인하지 못했습니다. 권한·TLS·접속 상태를 확인한 뒤 재실행하세요.")
    print("Database roles and schemas initialized. Disable enable_database_bootstrap_access after verification.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(failure_message(error), file=sys.stderr)
        sys.exit(1)
