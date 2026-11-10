-- 관리자(MASTER) 처리 이력 목록: 최신순(created_at DESC, history_id DESC).
-- 상태 이력 대부분은 USER·SYSTEM이라, 관리자 처리 행만 담는 부분 인덱스로 작게 유지한다.
-- 주문 ID로 거를 때는 기존 ix_exchange_order_status_histories_order(order_id, created_at)를 쓴다.
CREATE INDEX ix_exchange_order_status_histories_master
    ON exchange_order_status_histories (created_at DESC, history_id DESC)
    WHERE changed_by_type = 'MASTER';
