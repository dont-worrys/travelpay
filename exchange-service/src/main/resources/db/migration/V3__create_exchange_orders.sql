-- 견적 테이블은 MVP 이후 Redis로 옮기고 삭제할 예정이라 quote_id에 FK를 두지 않습니다.
CREATE TABLE exchange_orders (
    order_id               UUID           NOT NULL,
    user_id                UUID           NOT NULL,
    wallet_id              UUID           NOT NULL,
    idempotency_key        VARCHAR(100)   NOT NULL,
    quote_id               UUID           NOT NULL,
    quote_issued_at        TIMESTAMPTZ    NOT NULL,
    quote_expires_at       TIMESTAMPTZ    NOT NULL,
    rate_id                UUID           NOT NULL,
    rate_date              DATE           NOT NULL,
    applied_rate           NUMERIC(18, 8) NOT NULL,
    rate_unit              INT            NOT NULL,
    fee_rate               NUMERIC(7, 6)  NOT NULL,
    from_currency          CHAR(3)        NOT NULL,
    to_currency            CHAR(3)        NOT NULL,
    from_amount            BIGINT         NOT NULL,
    fee_amount             BIGINT         NOT NULL,
    to_amount              BIGINT         NOT NULL,
    total_debit_amount     BIGINT         NOT NULL,
    status                 VARCHAR(20)    NOT NULL,
    failure_reason         VARCHAR(30)    NULL,
    ledger_transaction_id  UUID           NULL,
    requested_at           TIMESTAMPTZ    NOT NULL,
    settled_at             TIMESTAMPTZ    NULL,
    ledger_check_count     INT            NOT NULL DEFAULT 0,
    last_ledger_checked_at TIMESTAMPTZ    NULL,
    created_at             TIMESTAMPTZ    NOT NULL,
    updated_at             TIMESTAMPTZ    NOT NULL,

    CONSTRAINT pk_exchange_orders PRIMARY KEY (order_id),
    -- 같은 견적은 한 번만 주문에 사용
    CONSTRAINT uk_exchange_orders_quote UNIQUE (quote_id),
    -- 같은 신청 요청의 재시도는 주문 하나만 생성
    CONSTRAINT uk_exchange_orders_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_exchange_orders_rate FOREIGN KEY (rate_id) REFERENCES exchange_rates (exchange_rate_id),
    CONSTRAINT ck_exchange_orders_currencies CHECK (
        from_currency IN ('KRW', 'JPY', 'CNY')
        AND to_currency IN ('KRW', 'JPY', 'CNY')
        AND (from_currency = 'KRW') <> (to_currency = 'KRW')
    ),
    CONSTRAINT ck_exchange_orders_amounts CHECK (from_amount > 0 AND to_amount > 0 AND fee_amount >= 0),
    CONSTRAINT ck_exchange_orders_total_debit CHECK (total_debit_amount = from_amount + fee_amount),
    CONSTRAINT ck_exchange_orders_rate CHECK (applied_rate > 0 AND rate_unit > 0),
    CONSTRAINT ck_exchange_orders_fee_rate CHECK (fee_rate >= 0 AND fee_rate < 1),
    CONSTRAINT ck_exchange_orders_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_exchange_orders_failure_reason
        CHECK (failure_reason IN ('INSUFFICIENT_BALANCE', 'WALLET_SUSPENDED', 'ACCOUNT_NOT_FOUND')),
    -- 실패 사유는 FAILED에만, 원장 거래 ID는 COMPLETED에만, 확정 시각은 처리 중이 아닐 때만 있음
    CONSTRAINT ck_exchange_orders_failed_has_reason CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CONSTRAINT ck_exchange_orders_completed_has_ledger
        CHECK ((status = 'COMPLETED') = (ledger_transaction_id IS NOT NULL)),
    CONSTRAINT ck_exchange_orders_settled_at CHECK ((status = 'PROCESSING') = (settled_at IS NULL)),
    -- 견적 유효 기간 안에 신청했음을 증빙
    CONSTRAINT ck_exchange_orders_requested_before_expiry CHECK (requested_at < quote_expires_at),
    CONSTRAINT ck_exchange_orders_ledger_check_count CHECK (ledger_check_count >= 0)
);

-- 복구 스케줄러: 처리 중 주문을 확인 횟수가 적은 순, 오래 확인하지 않은 순으로
CREATE INDEX ix_exchange_orders_processing
    ON exchange_orders (ledger_check_count, last_ledger_checked_at)
    WHERE status = 'PROCESSING';

-- 회원별 환전 내역 최신순
CREATE INDEX ix_exchange_orders_user_created ON exchange_orders (user_id, created_at DESC);

COMMENT ON TABLE exchange_orders IS '환전·재환전 주문. 견적 근거를 복사한 스냅샷으로 환전의 영구 근거. 환전/재환전은 to_currency로 구분';
COMMENT ON COLUMN exchange_orders.order_id IS '주문 ID. 원장 거래의 referenceId (referenceType=EXCHANGE)';
COMMENT ON COLUMN exchange_orders.wallet_id IS '지갑 ID (지갑 서비스). 원장 사용자 계정을 지갑 ID+통화로 지정';
COMMENT ON COLUMN exchange_orders.idempotency_key IS '신청 요청 멱등 키 (회원별 유일)';
COMMENT ON COLUMN exchange_orders.quote_id IS '근거 견적 ID. 견적 저장소 전환 대비 FK 없음';
COMMENT ON COLUMN exchange_orders.quote_issued_at IS '견적 발급 시각';
COMMENT ON COLUMN exchange_orders.quote_expires_at IS '견적 만료 시각';
COMMENT ON COLUMN exchange_orders.rate_id IS '근거 환율 ID';
COMMENT ON COLUMN exchange_orders.rate_date IS '적용 환율의 고시일';
COMMENT ON COLUMN exchange_orders.applied_rate IS '적용 환율 (매매기준율, 견적에서 복사)';
COMMENT ON COLUMN exchange_orders.rate_unit IS '적용 환율의 외화 단위 (JPY=100, CNY=1)';
COMMENT ON COLUMN exchange_orders.fee_rate IS '수수료율 (0.01 = 1%)';
COMMENT ON COLUMN exchange_orders.from_amount IS '보내는 금액 (from_currency 최소 단위). 원장 EXCHANGE_OUT';
COMMENT ON COLUMN exchange_orders.fee_amount IS '수수료 (from_currency 최소 단위). 원장 EXCHANGE_FEE';
COMMENT ON COLUMN exchange_orders.to_amount IS '받는 금액 (to_currency 최소 단위). 원장 EXCHANGE_IN';
COMMENT ON COLUMN exchange_orders.total_debit_amount IS '지갑에서 빠지는 금액 = from_amount + fee_amount';
COMMENT ON COLUMN exchange_orders.status IS 'PROCESSING(원장 결과 대기), COMPLETED(원장 기록), FAILED(원장 거절)';
COMMENT ON COLUMN exchange_orders.failure_reason IS '원장 거절 사유 (FAILED만)';
COMMENT ON COLUMN exchange_orders.ledger_transaction_id IS '원장 거래 ID (COMPLETED만). 원장 대사 연결 고리';
COMMENT ON COLUMN exchange_orders.requested_at IS '신청 시각. 견적 만료 검사에 쓴 시각';
COMMENT ON COLUMN exchange_orders.settled_at IS '원장 결과 확정 시각 (COMPLETED·FAILED)';
COMMENT ON COLUMN exchange_orders.ledger_check_count IS '원장 호출(등록·조회) 횟수';
COMMENT ON COLUMN exchange_orders.last_ledger_checked_at IS '마지막 원장 호출 시각';

CREATE TABLE exchange_order_status_histories (
    history_id      UUID        NOT NULL,
    order_id        UUID        NOT NULL,
    from_status     VARCHAR(20) NULL,
    to_status       VARCHAR(20) NOT NULL,
    reason          VARCHAR(50) NULL,
    changed_by_type VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_exchange_order_status_histories PRIMARY KEY (history_id),
    CONSTRAINT fk_exchange_order_status_histories_order
        FOREIGN KEY (order_id) REFERENCES exchange_orders (order_id),
    CONSTRAINT ck_exchange_order_status_histories_from_status
        CHECK (from_status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_exchange_order_status_histories_to_status
        CHECK (to_status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_exchange_order_status_histories_changed_by_type
        CHECK (changed_by_type IN ('USER', 'SYSTEM'))
);

CREATE INDEX ix_exchange_order_status_histories_order
    ON exchange_order_status_histories (order_id, created_at);

COMMENT ON TABLE exchange_order_status_histories IS '환전 주문 상태 변경 이력 (INSERT만)';
COMMENT ON COLUMN exchange_order_status_histories.from_status IS '이전 상태. 주문 생성이면 NULL';
COMMENT ON COLUMN exchange_order_status_histories.reason IS '변경 사유 (실패 사유 등)';
COMMENT ON COLUMN exchange_order_status_histories.changed_by_type IS 'USER(신청), SYSTEM(원장 결과 반영·복구 스케줄러)';
