\set ON_ERROR_STOP on
\getenv database_name POSTGRES_DB

-- DB 접속과 스키마 생성은 명시적으로 허용한 계정만 수행합니다.
REVOKE ALL ON DATABASE :"database_name" FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
\getenv app_username USER_DB_USERNAME
\getenv app_password USER_DB_PASSWORD
CREATE ROLE :"app_username" LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE SCHEMA user_service AUTHORIZATION :"app_username";
REVOKE ALL ON SCHEMA user_service FROM PUBLIC;
GRANT CONNECT ON DATABASE :"database_name" TO :"app_username";
ALTER ROLE :"app_username" IN DATABASE :"database_name" SET search_path TO user_service;

\getenv app_username WALLET_DB_USERNAME
\getenv app_password WALLET_DB_PASSWORD
CREATE ROLE :"app_username" LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE SCHEMA wallet_service AUTHORIZATION :"app_username";
REVOKE ALL ON SCHEMA wallet_service FROM PUBLIC;
GRANT CONNECT ON DATABASE :"database_name" TO :"app_username";
ALTER ROLE :"app_username" IN DATABASE :"database_name" SET search_path TO wallet_service;

\getenv app_username PAYMENT_DB_USERNAME
\getenv app_password PAYMENT_DB_PASSWORD
CREATE ROLE :"app_username" LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE SCHEMA payment_service AUTHORIZATION :"app_username";
REVOKE ALL ON SCHEMA payment_service FROM PUBLIC;
GRANT CONNECT ON DATABASE :"database_name" TO :"app_username";
ALTER ROLE :"app_username" IN DATABASE :"database_name" SET search_path TO payment_service;

\getenv app_username EXCHANGE_DB_USERNAME
\getenv app_password EXCHANGE_DB_PASSWORD
CREATE ROLE :"app_username" LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE SCHEMA exchange_service AUTHORIZATION :"app_username";
REVOKE ALL ON SCHEMA exchange_service FROM PUBLIC;
GRANT CONNECT ON DATABASE :"database_name" TO :"app_username";
ALTER ROLE :"app_username" IN DATABASE :"database_name" SET search_path TO exchange_service;

\getenv app_username LEDGER_DB_USERNAME
\getenv app_password LEDGER_DB_PASSWORD
CREATE ROLE :"app_username" LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE SCHEMA ledger_service AUTHORIZATION :"app_username";
REVOKE ALL ON SCHEMA ledger_service FROM PUBLIC;
GRANT CONNECT ON DATABASE :"database_name" TO :"app_username";
ALTER ROLE :"app_username" IN DATABASE :"database_name" SET search_path TO ledger_service;

\getenv app_username MOCK_BANK_DB_USERNAME
\getenv app_password MOCK_BANK_DB_PASSWORD
CREATE ROLE :"app_username" LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE SCHEMA mock_bank AUTHORIZATION :"app_username";
REVOKE ALL ON SCHEMA mock_bank FROM PUBLIC;
GRANT CONNECT ON DATABASE :"database_name" TO :"app_username";
ALTER ROLE :"app_username" IN DATABASE :"database_name" SET search_path TO mock_bank;
