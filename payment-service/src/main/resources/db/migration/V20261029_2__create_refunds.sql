-- 환불 요청 1건의 상세 내역입니다. 결제의 상태 변화는 payment_histories에 기록합니다.
CREATE TABLE refunds (
    id               UUID         NOT NULL,
    payment_id       UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    idempotency_key  VARCHAR(255) NOT NULL,
    amount           BIGINT       NOT NULL,
    currency         VARCHAR(3)   NOT NULL,
    reason           VARCHAR(255) NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    ledger_id        UUID,
    decline_reason   VARCHAR(255),
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    CONSTRAINT pk_refunds PRIMARY KEY (id),
    CONSTRAINT fk_refunds_payment_id FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT uk_refunds_user_id_idempotency_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_refunds_amount CHECK (amount > 0),
    CONSTRAINT ck_refunds_currency CHECK (currency IN ('KRW', 'JPY', 'CNY')),
    CONSTRAINT ck_refunds_status CHECK (status IN ('REQUESTED', 'COMPLETED', 'DECLINED'))
);

-- 결제별 환불 내역을 시간순으로 조회합니다.
CREATE INDEX ix_refunds_payment_id_created_at ON refunds (payment_id, created_at);
