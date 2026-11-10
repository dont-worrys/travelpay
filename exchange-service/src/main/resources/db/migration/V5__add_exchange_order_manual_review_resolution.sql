-- MANUAL_REVIEW 주문을 관리자(MASTER)가 원장 조회 결과에 따라 처리할 때 사용하는 실패 사유와 이력 컬럼을 추가한다.
-- 별도 처리 기록 테이블을 두지 않고 기존 주문 상태 이력(exchange_order_status_histories)에 관리자 처리 근거를 함께 남긴다.
ALTER TABLE exchange_orders DROP CONSTRAINT ck_exchange_orders_failure_reason;
ALTER TABLE exchange_orders
    ADD CONSTRAINT ck_exchange_orders_failure_reason
        CHECK (failure_reason IN (
            'INSUFFICIENT_BALANCE', 'WALLET_SUSPENDED', 'ACCOUNT_NOT_FOUND',
            'NOT_PROCESSED', 'PROCESSING_ERROR'
        ));

ALTER TABLE exchange_order_status_histories
    ADD COLUMN changed_by_id         UUID         NULL,
    ADD COLUMN ledger_transaction_id UUID         NULL,
    ADD COLUMN memo                  VARCHAR(255) NULL;

ALTER TABLE exchange_order_status_histories DROP CONSTRAINT ck_exchange_order_status_histories_changed_by_type;
ALTER TABLE exchange_order_status_histories
    ADD CONSTRAINT ck_exchange_order_status_histories_changed_by_type
        CHECK (changed_by_type IN ('USER', 'SYSTEM', 'MASTER')),
    ADD CONSTRAINT ck_exchange_order_status_histories_changed_by_id
        CHECK ((changed_by_type = 'MASTER') = (changed_by_id IS NOT NULL)),
    ADD CONSTRAINT ck_exchange_order_status_histories_master_only_fields
        CHECK (changed_by_type = 'MASTER' OR (ledger_transaction_id IS NULL AND memo IS NULL));

-- 관리자 MANUAL_REVIEW 주문 목록: 오래된 순(created_at, order_id)
CREATE INDEX ix_exchange_orders_manual_review
    ON exchange_orders (created_at, order_id)
    WHERE status = 'MANUAL_REVIEW';

COMMENT ON COLUMN exchange_order_status_histories.changed_by_type IS
    'USER(신청), SYSTEM(원장 결과 반영·복구 스케줄러), MASTER(MANUAL_REVIEW 관리자 처리)';
COMMENT ON COLUMN exchange_order_status_histories.changed_by_id IS '처리한 관리자 ID (MASTER일 때만)';
COMMENT ON COLUMN exchange_order_status_histories.ledger_transaction_id IS
    '관리자 처리 근거가 된 원장 거래 ID (MASTER 처리 시 POSTED·REJECTED면 기록, 기록 없음이면 NULL)';
COMMENT ON COLUMN exchange_order_status_histories.memo IS '관리자 처리 비고 (선택, 255자 이하, 고객에게 보이지 않음)';
