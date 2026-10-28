CREATE TABLE exchange_rates (
    exchange_rate_id UUID           NOT NULL,
    target_currency  CHAR(3)        NOT NULL,
    price_currency   CHAR(3)        NOT NULL DEFAULT 'KRW',
    unit             INT            NOT NULL,
    mid_rate         NUMERIC(18, 8) NOT NULL,
    buy_rate         NUMERIC(18, 8) NOT NULL,
    sell_rate        NUMERIC(18, 8) NOT NULL,
    source           VARCHAR(20)    NOT NULL,
    rate_date        DATE           NOT NULL,
    collected_at     TIMESTAMPTZ    NOT NULL,

    CONSTRAINT pk_exchange_rates PRIMARY KEY (exchange_rate_id),
    CONSTRAINT ck_exchange_rates_target_currency CHECK (target_currency IN ('JPY', 'CNY')),
    CONSTRAINT ck_exchange_rates_price_currency CHECK (price_currency = 'KRW'),
    CONSTRAINT ck_exchange_rates_unit CHECK (unit > 0),
    CONSTRAINT ck_exchange_rates_positive_rates CHECK (mid_rate > 0 AND buy_rate > 0 AND sell_rate > 0),
    CONSTRAINT ck_exchange_rates_source CHECK (source IN ('KOREAEXIM', 'MOCK'))
);

-- 하루 한 번 고시하는 공식 환율은 같은 통화·고시일을 한 건만 저장합니다.
CREATE UNIQUE INDEX uk_exchange_rates_koreaexim_daily
    ON exchange_rates (target_currency, rate_date)
    WHERE source = 'KOREAEXIM';

-- 출처·통화별 최신 환율 조회
CREATE INDEX ix_exchange_rates_latest
    ON exchange_rates (source, target_currency, rate_date DESC, collected_at DESC);

COMMENT ON TABLE exchange_rates IS '수집한 환율 이력. 출처·통화별 최신 행이 현재 환율';
COMMENT ON COLUMN exchange_rates.unit IS '고시 단위 (JPY=100, CNY=1). 환율 값은 unit 단위 외화의 원화 가격';
COMMENT ON COLUMN exchange_rates.mid_rate IS '매매기준율';
COMMENT ON COLUMN exchange_rates.buy_rate IS '고객이 외화를 살 때 환율 (TTS)';
COMMENT ON COLUMN exchange_rates.sell_rate IS '고객이 외화를 팔 때 환율 (TTB)';
COMMENT ON COLUMN exchange_rates.source IS '환율 출처: KOREAEXIM(한국수출입은행), MOCK(가짜 변동 환율)';
COMMENT ON COLUMN exchange_rates.rate_date IS '고시 날짜';
COMMENT ON COLUMN exchange_rates.collected_at IS '수집 시각';
