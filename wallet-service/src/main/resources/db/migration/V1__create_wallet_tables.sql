-- wallet-service: src/main/resources/db/migration/V1__create_wallet_tables.sql
-- 스키마: wallet_service

-- =========================================================
-- wallets: 회원당 1개
-- =========================================================
CREATE TABLE wallets (
     id          UUID         NOT NULL,
     user_id     UUID         NOT NULL,
     status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
     version     BIGINT       NOT NULL DEFAULT 0,
     created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
     updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

     CONSTRAINT pk_wallets PRIMARY KEY (id),
     CONSTRAINT uk_wallets_user UNIQUE (user_id),
     CONSTRAINT ck_wallets_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'CLOSED'))
);

-- =========================================================
-- wallet_currencies: 지갑 하나에 통화별 1행, 원장 계정과 1:1
-- =========================================================
CREATE TABLE wallet_currencies (
    id                 UUID         NOT NULL,
    wallet_id          UUID         NOT NULL,
    currency           CHAR(3)      NOT NULL,
    ledger_account_id  UUID         NOT NULL,   -- 원장 계정 개설 성공 후에만 행을 만듦
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_wallet_currencies PRIMARY KEY (id),
    CONSTRAINT uk_wallet_currencies_wallet_currency UNIQUE (wallet_id, currency),
    CONSTRAINT uk_wallet_currencies_ledger_account UNIQUE (ledger_account_id),
    CONSTRAINT fk_wallet_currencies_wallet
       FOREIGN KEY (wallet_id) REFERENCES wallets (id),
    CONSTRAINT ck_wallet_currencies_currency CHECK (currency IN ('KRW', 'JPY', 'CNY')),
    CONSTRAINT ck_wallet_currencies_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

-- =========================================================
-- charge_requests: 충전 요청 (SAGA 상태 보관)
-- =========================================================
CREATE TABLE charge_requests (
     id                    UUID          NOT NULL,
     user_id               UUID          NOT NULL,
     wallet_id             UUID          NOT NULL,   -- 추가: 원장 적립 시 계정 조회용
     amount                BIGINT        NOT NULL,
     currency              CHAR(3)       NOT NULL,
     bank_code             VARCHAR(10)   NOT NULL,
     account_number_enc    TEXT          NOT NULL,
     holder_name_snapshot  VARCHAR(100)  NOT NULL,   -- 실명 조회 실패 시 저장하지 않으므로 항상 있음
     status                VARCHAR(30)   NOT NULL DEFAULT 'REQUESTED',
     idempotency_key       UUID          NOT NULL,
     bank_request_id       VARCHAR(100)  NOT NULL,   -- 접수 시 생성·고정
     refund_request_id     VARCHAR(100)  NOT NULL,   -- 접수 시 생성·고정
     bank_tx_id            UUID          NULL,       -- 은행 출금 성공 후
     refund_bank_tx_id     UUID          NULL,       -- 은행 환불 성공 후
     ledger_ref            UUID          NULL,       -- 원장 거래 ID, 적립 성공 후
     failure_code          VARCHAR(50)   NULL,
     last_error            VARCHAR(500)  NULL,
     attempt_count         INT           NOT NULL DEFAULT 0,
     created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
     updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),

     CONSTRAINT pk_charge_requests PRIMARY KEY (id),
     CONSTRAINT uk_charge_requests_idempotency UNIQUE (user_id, idempotency_key),
     CONSTRAINT uk_charge_requests_bank_request UNIQUE (bank_request_id),
     CONSTRAINT uk_charge_requests_refund_request UNIQUE (refund_request_id),
     CONSTRAINT uk_charge_requests_bank_tx UNIQUE (bank_tx_id),
     CONSTRAINT uk_charge_requests_refund_bank_tx UNIQUE (refund_bank_tx_id),
     CONSTRAINT fk_charge_requests_wallet
         FOREIGN KEY (wallet_id) REFERENCES wallets (id),

     CONSTRAINT ck_charge_requests_amount CHECK (amount > 0),
     CONSTRAINT ck_charge_requests_currency CHECK (currency IN ('KRW', 'JPY', 'CNY')),
     CONSTRAINT ck_charge_requests_status CHECK (status IN (
                                                            'REQUESTED', 'BANK_PROCESSING', 'CREDIT_PROCESSING', 'COMPLETED',
                                                            'FAILED', 'REFUND_PROCESSING', 'REFUNDED', 'MANUAL_REVIEW')),
     CONSTRAINT ck_charge_requests_attempt_count CHECK (attempt_count >= 0)
);

-- 복구 스케줄러: 처리 중 상태로 오래 머문 건 찾기
CREATE INDEX ix_charge_requests_status_updated ON charge_requests (status, updated_at);
-- 충전 내역 조회(P1): 본인 목록 최신순
CREATE INDEX ix_charge_requests_user_created ON charge_requests (user_id, created_at DESC);

-- =========================================================
-- charge_status_history: 상태 전이 이력
-- =========================================================
CREATE TABLE charge_status_history (
   id             UUID          NOT NULL,
   charge_id      UUID          NOT NULL,
   from_status    VARCHAR(30)   NULL,       -- 최초 접수면 NULL
   to_status      VARCHAR(30)   NOT NULL,
   reason_code    VARCHAR(50)   NULL,
   reason_detail  VARCHAR(500)  NULL,
   trace_id       VARCHAR(64)   NULL,
   created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),

   CONSTRAINT pk_charge_status_history PRIMARY KEY (id),
   CONSTRAINT fk_charge_status_history_charge
       FOREIGN KEY (charge_id) REFERENCES charge_requests (id)
);

CREATE INDEX ix_charge_status_history_charge ON charge_status_history (charge_id, created_at);

-- =========================================================
-- outbox_events: 범용 Outbox (MVP는 CHARGE만, 이후 재사용)
-- =========================================================
CREATE TABLE outbox_events (
   id              UUID         NOT NULL,
   aggregate_type  VARCHAR(30)  NOT NULL,
   aggregate_id    UUID         NOT NULL,
   event_type      VARCHAR(50)  NOT NULL,
   payload         JSONB        NOT NULL,
   status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
   retry_count     INT          NOT NULL DEFAULT 0,
   created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
   published_at    TIMESTAMPTZ  NULL,

   CONSTRAINT pk_outbox_events PRIMARY KEY (id),
   CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'PUBLISHED')),
   CONSTRAINT ck_outbox_events_published_at
       CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);

-- 발행기: 미발행 건만 오래된 순으로
CREATE INDEX ix_outbox_events_pending ON outbox_events (created_at) WHERE status = 'PENDING';