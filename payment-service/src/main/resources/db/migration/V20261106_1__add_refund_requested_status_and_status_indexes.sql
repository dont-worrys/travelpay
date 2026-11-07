-- 고객 환불 신청 상태 추가 (APPROVED → REFUND_REQUESTED → REFUND_PENDING → REFUNDED)
ALTER TABLE payments DROP CONSTRAINT ck_payments_status;
ALTER TABLE payments ADD CONSTRAINT ck_payments_status
    CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'REFUND_REQUESTED', 'REFUND_PENDING', 'REFUNDED', 'MANUAL_REVIEW'));

-- 이력 처리자에 가맹점(주인) 추가. 고객 취소(USER)와 가맹점 거절(MERCHANT)을 이력에서 구분합니다.
ALTER TABLE payment_histories DROP CONSTRAINT ck_payment_histories_created_by;
ALTER TABLE payment_histories ADD CONSTRAINT ck_payment_histories_created_by
    CHECK (created_by IN ('USER', 'MERCHANT', 'SYSTEM', 'MASTER'));

-- 아래는 특정 상태의 행만 담는 부분 인덱스입니다. 해당 상태의 결제는 평소 적어서 인덱스가 작고, 결제 저장에 주는 부담도 거의 없습니다.

-- 결제 복구 대상 조회(10초마다, updated_at·id 순), 30분 넘은 PENDING 건수
CREATE INDEX ix_payments_pending_updated_at_id ON payments (updated_at, id) WHERE status = 'PENDING';
-- 결제 MANUAL_REVIEW 건수(10초마다), 운영자 목록(오래된 순)
CREATE INDEX ix_payments_manual_review_created_at_id ON payments (created_at, id) WHERE status = 'MANUAL_REVIEW';
-- 3일 넘게 처리되지 않은 환불 신청 조회
CREATE INDEX ix_payments_refund_requested_updated_at_id ON payments (updated_at, id) WHERE status = 'REFUND_REQUESTED';
-- 가맹점의 환불 신청 목록 (가맹점 결제 목록 + status=REFUND_REQUESTED, 최신순)
CREATE INDEX ix_payments_refund_requested_merchant_id_created_at_id
    ON payments (merchant_id, created_at DESC, id DESC) WHERE status = 'REFUND_REQUESTED';

-- 환불 복구 대상 조회(10초마다), 30분 넘은 REQUESTED 건수
CREATE INDEX ix_refunds_requested_updated_at_id ON refunds (updated_at, id) WHERE status = 'REQUESTED';
-- 환불 MANUAL_REVIEW 건수(10초마다), 운영자 목록
CREATE INDEX ix_refunds_manual_review_created_at_id ON refunds (created_at, id) WHERE status = 'MANUAL_REVIEW';
