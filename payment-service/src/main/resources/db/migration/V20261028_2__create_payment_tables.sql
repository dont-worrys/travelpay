-- 가맹점. 가맹점 정책 확정 전까지 결제 검증용 데이터로 사용합니다.
CREATE TABLE merchants (
    id          UUID         NOT NULL,
    name        VARCHAR(255) NOT NULL,
    currency    VARCHAR(3)   NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT pk_merchants PRIMARY KEY (id),
    CONSTRAINT ck_merchants_currency CHECK (currency IN ('KRW', 'JPY', 'CNY')),
    CONSTRAINT ck_merchants_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

-- 결제. 회원·지갑·원장은 다른 서비스 소유이므로 FK 없이 ID 값만 저장합니다.
CREATE TABLE payments (
    id               UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    user_wallet_id   UUID,
    idempotency_key  VARCHAR(255) NOT NULL,
    merchant_id      UUID         NOT NULL,
    merchant_name    VARCHAR(255),
    payment_info     VARCHAR(255),
    currency         VARCHAR(3)   NOT NULL,
    amount           BIGINT       NOT NULL,
    ledger_id        UUID,
    decline_reason   VARCHAR(255),
    status           VARCHAR(20)  NOT NULL,
    version          INTEGER      NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT uk_payments_user_id_idempotency_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_currency CHECK (currency IN ('KRW', 'JPY', 'CNY')),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'REFUND_PENDING', 'REFUNDED'))
);

-- 결제 상태 변경 이력. 생성(null → PENDING)과 이후 상태 변경을 모두 기록합니다.
CREATE TABLE payment_histories (
    id               UUID         NOT NULL,
    payment_id       UUID         NOT NULL,
    previous_status  VARCHAR(20),
    current_status   VARCHAR(20)  NOT NULL,
    reason           VARCHAR(255),
    created_by       VARCHAR(20)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    CONSTRAINT pk_payment_histories PRIMARY KEY (id),
    CONSTRAINT fk_payment_histories_payment_id FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT ck_payment_histories_created_by CHECK (created_by IN ('USER', 'SYSTEM', 'MASTER'))
);

-- 결제별 이력을 시간순으로 조회합니다.
CREATE INDEX ix_payment_histories_payment_id_created_at ON payment_histories (payment_id, created_at);
