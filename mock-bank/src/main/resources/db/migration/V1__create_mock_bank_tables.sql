-- mock-bank: src/main/resources/db/migration/V1__create_mock_bank_tables.sql
-- 스키마: mock_bank

-- =========================================================
-- bank_accounts: 사용자 계좌 + 플랫폼 계좌
-- =========================================================
CREATE TABLE bank_accounts (
id              UUID          NOT NULL,
bank_code       VARCHAR(10)   NOT NULL,
account_number  VARCHAR(20)   NOT NULL,
holder_name     VARCHAR(100)  NOT NULL,
owner_type      VARCHAR(20)   NOT NULL,
balance         BIGINT        NOT NULL DEFAULT 0,
status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

CONSTRAINT pk_bank_accounts PRIMARY KEY (id),
CONSTRAINT uk_bank_accounts_number UNIQUE (bank_code, account_number),
CONSTRAINT ck_bank_accounts_owner_type CHECK (owner_type IN ('USER', 'PLATFORM')),
CONSTRAINT ck_bank_accounts_balance CHECK (balance >= 0),
CONSTRAINT ck_bank_accounts_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

-- =========================================================
-- bank_transactions: 출금·환불 기록 (업무 거절도 FAILED로 남김)
-- =========================================================
CREATE TABLE bank_transactions (
id               UUID          NOT NULL,
request_id       VARCHAR(100)  NOT NULL,   -- 호출 측 요청 ID, 멱등 키
type             VARCHAR(20)   NOT NULL,
from_account_id  UUID          NULL,       -- 계좌 없음으로 거절되면 NULL
to_account_id    UUID          NULL,
amount           BIGINT        NOT NULL,
status           VARCHAR(20)   NOT NULL,
failure_code     VARCHAR(50)   NULL,
original_tx_id   UUID          NULL,       -- 환불만 사용
created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
completed_at     TIMESTAMPTZ   NULL,

CONSTRAINT pk_bank_transactions PRIMARY KEY (id),
CONSTRAINT uk_bank_transactions_request UNIQUE (request_id),
CONSTRAINT fk_bank_transactions_from
   FOREIGN KEY (from_account_id) REFERENCES bank_accounts (id),
CONSTRAINT fk_bank_transactions_to
   FOREIGN KEY (to_account_id) REFERENCES bank_accounts (id),
CONSTRAINT fk_bank_transactions_original
   FOREIGN KEY (original_tx_id) REFERENCES bank_transactions (id),

CONSTRAINT ck_bank_transactions_type CHECK (type IN ('DEBIT', 'REFUND')),
CONSTRAINT ck_bank_transactions_status CHECK (status IN ('SUCCEEDED', 'FAILED')),
CONSTRAINT ck_bank_transactions_amount CHECK (amount > 0),
-- FAILED일 때만 실패 코드가 있음
CONSTRAINT ck_bank_transactions_failure_code
   CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)),
-- 성공한 거래는 양쪽 계좌가 반드시 있음
CONSTRAINT ck_bank_transactions_succeeded_accounts
   CHECK (status <> 'SUCCEEDED' OR (from_account_id IS NOT NULL AND to_account_id IS NOT NULL)),
-- 출금에는 원거래가 없음
CONSTRAINT ck_bank_transactions_debit_original
   CHECK (type <> 'DEBIT' OR original_tx_id IS NULL)
);

-- 원거래당 성공한 환불은 1회 (실패한 환불 기록은 막지 않음)
CREATE UNIQUE INDEX uk_bank_transactions_refund_once
    ON bank_transactions (original_tx_id)
    WHERE type = 'REFUND' AND status = 'SUCCEEDED';