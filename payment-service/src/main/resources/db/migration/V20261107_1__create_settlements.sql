-- 가맹점 정산: 가맹점 주인·MASTER가 요청하면 환불 기간이 지난 미정산 승인 결제를 모아
-- 가맹점 원장 지갑에서 주인 회원 지갑으로 보냅니다.
CREATE TABLE settlements (
    id                    UUID PRIMARY KEY,
    merchant_id           UUID         NOT NULL,
    requested_by          UUID         NOT NULL,  -- 요청자(가맹점 주인 또는 MASTER)
    idempotency_key       VARCHAR(255) NOT NULL,
    owner_user_id         UUID         NOT NULL,  -- 정산을 받는 가맹점 주인 (요청 시점)
    owner_wallet_id       UUID         NOT NULL,  -- 받는 지갑. 결과 모름 재전송도 이 값으로 보냄
    currency              VARCHAR(3)   NOT NULL,
    amount                BIGINT       NOT NULL,
    cutoff_at             TIMESTAMPTZ  NOT NULL,  -- (승인 시각 + 환불 기간 + 여유 시간)이 이 시각 전인 결제만 포함
    status                VARCHAR(20)  NOT NULL,
    ledger_transaction_id UUID,
    failure_reason        VARCHAR(255),
    attempt_count         INT          NOT NULL DEFAULT 0,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_settlements_merchant_id FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT uk_settlements_requested_by_idempotency_key UNIQUE (requested_by, idempotency_key),
    CONSTRAINT ck_settlements_status CHECK (status IN ('REQUESTED', 'PAID', 'FAILED')),
    CONSTRAINT ck_settlements_amount CHECK (amount > 0),
    CONSTRAINT ck_settlements_currency CHECK (currency IN ('KRW', 'JPY', 'CNY')),
    CONSTRAINT ck_settlements_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_settlements_paid_has_ledger_transaction
        CHECK (status <> 'PAID' OR ledger_transaction_id IS NOT NULL)
);

-- 정산에 들어간 결제. 결제 하나는 한 번만 정산됩니다(PK).
-- 원장이 정산을 거절하면(FAILED) 항목을 지워 다음 요청에서 다시 대상이 됩니다.
CREATE TABLE settlement_items (
    payment_id    UUID PRIMARY KEY,
    settlement_id UUID        NOT NULL,
    amount        BIGINT      NOT NULL,  -- 결제 금액 - 완료된 환불 합계
    created_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_settlement_items_payment_id FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT fk_settlement_items_settlement_id FOREIGN KEY (settlement_id) REFERENCES settlements (id),
    CONSTRAINT ck_settlement_items_amount CHECK (amount > 0)
);

-- 정산 상세의 포함 결제 조회, 거절 시 항목 삭제
CREATE INDEX ix_settlement_items_settlement_id ON settlement_items (settlement_id);
-- 결과 모름 정산 복구 대상 (REQUESTED, 수정 시각 오래된 순)
CREATE INDEX ix_settlements_requested_updated_at_id ON settlements (updated_at, id) WHERE status = 'REQUESTED';
-- 가맹점별 정산 목록 (최신순)
CREATE INDEX ix_settlements_merchant_id_created_at_id ON settlements (merchant_id, created_at DESC, id DESC);
