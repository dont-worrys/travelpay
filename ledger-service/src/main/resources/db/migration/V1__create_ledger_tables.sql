-- ledger-service: src/main/resources/db/migration/V1__create_ledger_tables.sql
-- 스키마: ledger_service (spring.flyway.default-schema로 지정, 테이블명에 스키마 접두사 없음)

-- =========================================================
-- ledger_accounts: 계정(통)마다 현재 잔액
-- =========================================================
CREATE TABLE ledger_accounts (
     id            UUID         NOT NULL,
     owner_type    VARCHAR(20)  NOT NULL,
     wallet_id     UUID         NULL,          -- USER만 값 있음, SYSTEM은 NULL
     currency      CHAR(3)      NOT NULL,
     account_type  VARCHAR(30)  NOT NULL,
     balance       BIGINT       NOT NULL DEFAULT 0,
     status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
     created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
     updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

     CONSTRAINT pk_ledger_accounts PRIMARY KEY (id),
-- ledger_entries의 (account_id, currency) 복합 FK 대상
     CONSTRAINT uk_ledger_accounts_id_currency UNIQUE (id, currency),

     CONSTRAINT ck_ledger_accounts_owner_type
         CHECK (owner_type IN ('USER', 'SYSTEM', 'MERCHANT')),
     CONSTRAINT ck_ledger_accounts_currency
         CHECK (currency IN ('KRW', 'JPY', 'CNY')),
     CONSTRAINT ck_ledger_accounts_account_type
         CHECK (account_type IN ('WALLET', 'CHARGE_SOURCE', 'FX_POSITION', 'FEE_REVENUE', 'MERCHANT_SETTLEMENT')),
     CONSTRAINT ck_ledger_accounts_status
         CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
-- USER 계정만 잔액 음수 금지 (시스템 계정은 음수 허용)
     CONSTRAINT ck_ledger_accounts_user_balance
         CHECK (owner_type <> 'USER' OR balance >= 0),
-- USER는 지갑 필수, SYSTEM은 지갑 없음 (MERCHANT 식별 방식은 MVP 이후 결정)
     CONSTRAINT ck_ledger_accounts_user_wallet
         CHECK (owner_type <> 'USER' OR wallet_id IS NOT NULL),
     CONSTRAINT ck_ledger_accounts_system_wallet
         CHECK (owner_type <> 'SYSTEM' OR wallet_id IS NULL)
);

-- 사용자 계정 중복 방지: 지갑 하나에 같은 유형·통화 계정은 1개 (계정 개설 멱등 근거)
CREATE UNIQUE INDEX uk_ledger_accounts_wallet
    ON ledger_accounts (wallet_id, account_type, currency)
    WHERE wallet_id IS NOT NULL;

-- 시스템 계정 중복 방지 (wallet_id가 NULL이라 일반 UNIQUE로는 막히지 않음)
CREATE UNIQUE INDEX uk_ledger_accounts_system
    ON ledger_accounts (account_type, currency)
    WHERE owner_type = 'SYSTEM';

-- =========================================================
-- ledger_transactions: 돈이 움직인 사건 (INSERT만)
-- =========================================================
CREATE TABLE ledger_transactions (
     id                       UUID          NOT NULL,
     reference_type           VARCHAR(30)   NOT NULL,
     reference_id             UUID          NOT NULL,
     request_hash             VARCHAR(64)   NOT NULL,
     user_id                  UUID          NULL,
     status                   VARCHAR(20)   NOT NULL,
     reject_reason            VARCHAR(50)   NULL,
     original_transaction_id  UUID          NULL,
     description              VARCHAR(255)  NULL,
     created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),

     CONSTRAINT pk_ledger_transactions PRIMARY KEY (id),
-- 원장 멱등 키
     CONSTRAINT uk_ledger_transactions_reference UNIQUE (reference_type, reference_id),
     CONSTRAINT fk_ledger_transactions_original
         FOREIGN KEY (original_transaction_id) REFERENCES ledger_transactions (id),

     CONSTRAINT ck_ledger_transactions_reference_type
         CHECK (reference_type IN ('CHARGE', 'PAYMENT', 'PAYMENT_CANCEL', 'TRANSFER', 'EXCHANGE')),
     CONSTRAINT ck_ledger_transactions_status
         CHECK (status IN ('POSTED', 'REJECTED')),
-- REJECTED일 때만 거절 사유가 있음
     CONSTRAINT ck_ledger_transactions_reject_reason
         CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL))
);

-- =========================================================
-- ledger_entries: 어느 계정에서 얼마 빠지고 들어갔는지 (INSERT만)
-- =========================================================
CREATE TABLE ledger_entries (
    id              UUID         NOT NULL,
    transaction_id  UUID         NOT NULL,
    account_id      UUID         NOT NULL,
    currency        CHAR(3)      NOT NULL,
    entry_type      VARCHAR(30)  NOT NULL,   -- 값 목록은 10/30 취합 후 CHECK 추가
    direction       VARCHAR(10)  NOT NULL,
    amount          BIGINT       NOT NULL,
    balance_after   BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT fk_ledger_entries_transaction
        FOREIGN KEY (transaction_id) REFERENCES ledger_transactions (id),
-- 항목 통화가 계정 통화와 다르면 저장 자체가 안 됨
    CONSTRAINT fk_ledger_entries_account
        FOREIGN KEY (account_id, currency) REFERENCES ledger_accounts (id, currency),

    CONSTRAINT ck_ledger_entries_direction
        CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_amount
        CHECK (amount > 0)
);

CREATE INDEX ix_ledger_entries_transaction ON ledger_entries (transaction_id);
CREATE INDEX ix_ledger_entries_account_created ON ledger_entries (account_id, created_at);