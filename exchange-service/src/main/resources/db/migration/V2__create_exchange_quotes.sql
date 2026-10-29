CREATE TABLE exchange_quotes (
    quote_id           UUID           NOT NULL,
    quote_type         VARCHAR(20)    NOT NULL,
    rate_id            UUID           NOT NULL,
    user_id            UUID           NOT NULL,
    from_currency      CHAR(3)        NOT NULL,
    to_currency        CHAR(3)        NOT NULL,
    from_amount        BIGINT         NOT NULL,
    to_amount          BIGINT         NOT NULL,
    applied_rate       NUMERIC(18, 8) NOT NULL,
    rate_unit          INT            NOT NULL,
    rate_date          DATE           NOT NULL,
    fee_rate           NUMERIC(7, 6)  NOT NULL,
    fee_amount         BIGINT         NOT NULL,
    total_debit_amount BIGINT         NOT NULL,
    status             VARCHAR(20)    NOT NULL,
    expires_at         TIMESTAMPTZ    NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,

    CONSTRAINT pk_exchange_quotes PRIMARY KEY (quote_id),
    CONSTRAINT fk_exchange_quotes_rate FOREIGN KEY (rate_id) REFERENCES exchange_rates (exchange_rate_id),
    CONSTRAINT ck_exchange_quotes_quote_type CHECK (quote_type IN ('USER', 'RESERVATION')),
    CONSTRAINT ck_exchange_quotes_currencies CHECK (
        from_currency IN ('KRW', 'JPY', 'CNY')
        AND to_currency IN ('KRW', 'JPY', 'CNY')
        AND (from_currency = 'KRW') <> (to_currency = 'KRW')
    ),
    CONSTRAINT ck_exchange_quotes_amounts CHECK (from_amount > 0 AND to_amount > 0 AND fee_amount >= 0),
    CONSTRAINT ck_exchange_quotes_total_debit CHECK (total_debit_amount = from_amount + fee_amount),
    CONSTRAINT ck_exchange_quotes_rate CHECK (applied_rate > 0 AND rate_unit > 0),
    CONSTRAINT ck_exchange_quotes_fee_rate CHECK (fee_rate >= 0 AND fee_rate < 1),
    CONSTRAINT ck_exchange_quotes_status CHECK (status IN ('ISSUED', 'USED'))
);

COMMENT ON TABLE exchange_quotes IS '환전 견적. 만료 전까지 금액을 보장하며, 주문은 견적 금액을 그대로 복사';
COMMENT ON COLUMN exchange_quotes.quote_type IS 'USER(사용자 요청), RESERVATION(예약 체결 시 시스템 발급)';
COMMENT ON COLUMN exchange_quotes.rate_id IS '근거 환율 ID';
COMMENT ON COLUMN exchange_quotes.user_id IS '회원 ID (회원 서비스). 지갑은 주문할 때 받음';
COMMENT ON COLUMN exchange_quotes.from_amount IS '보내는 금액 (from_currency 최소 단위)';
COMMENT ON COLUMN exchange_quotes.to_amount IS '받는 금액 (to_currency 최소 단위, 내림)';
COMMENT ON COLUMN exchange_quotes.applied_rate IS '적용 환율 (수집 테이블의 mid_rate(매매기준율) 복사, 살 때·팔 때 동일)';
COMMENT ON COLUMN exchange_quotes.rate_unit IS '적용 환율의 외화 단위 (JPY=100, CNY=1)';
COMMENT ON COLUMN exchange_quotes.rate_date IS '적용 환율의 고시일';
COMMENT ON COLUMN exchange_quotes.fee_rate IS '발급 시 수수료율 (0.01 = 1%)';
COMMENT ON COLUMN exchange_quotes.fee_amount IS '수수료 (from_currency 최소 단위, 올림)';
COMMENT ON COLUMN exchange_quotes.total_debit_amount IS '지갑에서 빠질 금액 = from_amount + fee_amount';
COMMENT ON COLUMN exchange_quotes.status IS 'ISSUED(발급), USED(주문에 사용). 만료는 expires_at으로 판단';
COMMENT ON COLUMN exchange_quotes.expires_at IS '만료 시각';
