-- 원장이 400·409처럼 다시 보내도 풀리지 않는 응답을 주거나, 오래 결과를 확인하지 못한 주문은 사람이 확인한다.
-- MANUAL_REVIEW는 확정이 아니므로 확정 시각(settled_at)은 COMPLETED·FAILED에만 있다. 들어간 이유는 상태 이력에 남긴다.
ALTER TABLE exchange_orders DROP CONSTRAINT ck_exchange_orders_status;
ALTER TABLE exchange_orders
    ADD CONSTRAINT ck_exchange_orders_status
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'MANUAL_REVIEW'));

ALTER TABLE exchange_orders DROP CONSTRAINT ck_exchange_orders_settled_at;
ALTER TABLE exchange_orders
    ADD CONSTRAINT ck_exchange_orders_settled_at
        CHECK ((status IN ('COMPLETED', 'FAILED')) = (settled_at IS NOT NULL));

ALTER TABLE exchange_order_status_histories DROP CONSTRAINT ck_exchange_order_status_histories_from_status;
ALTER TABLE exchange_order_status_histories
    ADD CONSTRAINT ck_exchange_order_status_histories_from_status
        CHECK (from_status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'MANUAL_REVIEW'));

ALTER TABLE exchange_order_status_histories DROP CONSTRAINT ck_exchange_order_status_histories_to_status;
ALTER TABLE exchange_order_status_histories
    ADD CONSTRAINT ck_exchange_order_status_histories_to_status
        CHECK (to_status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'MANUAL_REVIEW'));

COMMENT ON COLUMN exchange_orders.status IS
    'PROCESSING(원장 결과 대기), COMPLETED(원장 기록), FAILED(원장 거절), MANUAL_REVIEW(사람 확인 필요)';
COMMENT ON COLUMN exchange_orders.settled_at IS '원장 결과 확정 시각 (COMPLETED·FAILED만)';
COMMENT ON COLUMN exchange_order_status_histories.reason IS '변경 사유 (실패 사유, 사람 확인으로 보낸 이유 등)';
