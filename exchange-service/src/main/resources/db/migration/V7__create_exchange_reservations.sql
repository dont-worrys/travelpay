-- 예약 환전(#240): 목표 환율에 닿으면 자동으로 환전 주문을 만든다. 예약할 때는 돈이 움직이지 않는다.
-- 체결은 보통 환전과 같은 주문(exchange_orders)을 만들고, 그 주문 ID를 order_id에 남긴다.
CREATE TABLE exchange_reservations (
    reservation_id  UUID           NOT NULL,
    user_id         UUID           NOT NULL,
    wallet_id       UUID           NOT NULL,
    idempotency_key VARCHAR(100)   NOT NULL,
    from_currency   CHAR(3)        NOT NULL,
    to_currency     CHAR(3)        NOT NULL,
    from_amount     BIGINT         NOT NULL,
    target_rate     NUMERIC(18, 8) NOT NULL,
    rate_unit       INT            NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    expires_at      TIMESTAMPTZ    NOT NULL,
    order_id        UUID           NULL,
    executed_at     TIMESTAMPTZ    NULL,
    created_at      TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL,

    CONSTRAINT pk_exchange_reservations PRIMARY KEY (reservation_id),
    -- 같은 예약 요청의 재시도는 예약 하나만 생성
    CONSTRAINT uk_exchange_reservations_idempotency UNIQUE (user_id, idempotency_key),
    -- 예약 하나에 주문 하나
    CONSTRAINT uk_exchange_reservations_order UNIQUE (order_id),
    CONSTRAINT fk_exchange_reservations_order FOREIGN KEY (order_id) REFERENCES exchange_orders (order_id),
    CONSTRAINT ck_exchange_reservations_currencies CHECK (
        from_currency IN ('KRW', 'JPY', 'CNY')
        AND to_currency IN ('KRW', 'JPY', 'CNY')
        AND (from_currency = 'KRW') <> (to_currency = 'KRW')
    ),
    CONSTRAINT ck_exchange_reservations_amount CHECK (from_amount > 0),
    CONSTRAINT ck_exchange_reservations_rate CHECK (target_rate > 0 AND rate_unit > 0),
    CONSTRAINT ck_exchange_reservations_status CHECK (status IN ('WAITING', 'EXECUTED', 'CANCELED', 'EXPIRED')),
    -- 체결된 예약만 주문과 체결 시각이 있다 (체결과 주문 연결은 한 번의 UPDATE로 함께 기록)
    CONSTRAINT ck_exchange_reservations_executed
        CHECK ((status = 'EXECUTED') = (order_id IS NOT NULL) AND (status = 'EXECUTED') = (executed_at IS NOT NULL)),
    CONSTRAINT ck_exchange_reservations_expiry CHECK (expires_at > created_at)
);

-- 조건 매칭: 수집한 환율로 체결할 대기 예약 찾기. 통화쌍은 같음, 목표 환율은 범위로 비교
--   원화 → 외화: target_rate >= 현재 환율 / 외화 → 원화: target_rate <= 현재 환율
CREATE INDEX ix_exchange_reservations_waiting_match
    ON exchange_reservations (from_currency, to_currency, target_rate)
    WHERE status = 'WAITING';

-- 만료 처리: 만료 시각이 지난 대기 예약
CREATE INDEX ix_exchange_reservations_waiting_expiry
    ON exchange_reservations (expires_at)
    WHERE status = 'WAITING';

-- 회원별 예약 목록 최신순, 회원별 대기 예약 수 확인
CREATE INDEX ix_exchange_reservations_user_created ON exchange_reservations (user_id, created_at DESC);

COMMENT ON TABLE exchange_reservations IS '예약 환전. 목표 환율에 닿으면 보통 환전과 같은 주문을 만들어 체결';
COMMENT ON COLUMN exchange_reservations.idempotency_key IS '예약 요청 멱등 키 (회원별 유일)';
COMMENT ON COLUMN exchange_reservations.from_amount IS '보내는 금액 (from_currency 최소 단위). 체결 때 이 금액으로 견적을 계산';
COMMENT ON COLUMN exchange_reservations.target_rate IS '목표 매매기준율 (rate_unit 단위 외화의 원화 가격)';
COMMENT ON COLUMN exchange_reservations.rate_unit IS '목표 환율의 외화 단위 (JPY=100, CNY=1)';
COMMENT ON COLUMN exchange_reservations.status IS
    'WAITING(대기), EXECUTED(체결: 주문 생성), CANCELED(사용자 취소·지갑 해지), EXPIRED(만료)';
COMMENT ON COLUMN exchange_reservations.order_id IS '체결로 만든 주문 ID (EXECUTED만). 결과(완료·실패)는 주문 상태로 확인';
COMMENT ON COLUMN exchange_reservations.executed_at IS '체결 시각 (EXECUTED만)';
