-- 결제·환불 복구: 원장 결과 모름 재시도 횟수와 자동 처리 중단(MANUAL_REVIEW)을 추가합니다. 송금·지갑 충전 복구와 같은 방식입니다.
-- attempt_count: 원장 요청 결과를 확인하지 못한 횟수. 재시도 대기 시간(30초 × 2^(attempt_count - 1))과 한도 판단에 씁니다.
-- review_reason: 자동 처리를 멈춘 사유이며 MANUAL_REVIEW일 때만 있습니다.
--   LEDGER_REQUEST_NOT_ACCEPTED: 원장이 요청을 받아들이지 않음(409·400 등). 재요청해도 같은 결과라 재시도하지 않습니다.
--   RETRY_EXHAUSTED: 결과 모름이 한도에 이름. 원장 반영 여부를 확인해야 합니다.

-- 결제
ALTER TABLE payments ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE payments ADD COLUMN review_reason VARCHAR(30);

ALTER TABLE payments ADD CONSTRAINT ck_payments_attempt_count CHECK (attempt_count >= 0);

ALTER TABLE payments DROP CONSTRAINT ck_payments_status;
ALTER TABLE payments ADD CONSTRAINT ck_payments_status
    CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'REFUND_PENDING', 'REFUNDED', 'MANUAL_REVIEW'));

ALTER TABLE payments ADD CONSTRAINT ck_payments_review_reason
    CHECK (review_reason IN ('LEDGER_REQUEST_NOT_ACCEPTED', 'RETRY_EXHAUSTED'));
ALTER TABLE payments ADD CONSTRAINT ck_payments_manual_review_has_reason
    CHECK ((status = 'MANUAL_REVIEW') = (review_reason IS NOT NULL));

-- 환불
ALTER TABLE refunds ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE refunds ADD COLUMN review_reason VARCHAR(30);

ALTER TABLE refunds ADD CONSTRAINT ck_refunds_attempt_count CHECK (attempt_count >= 0);

ALTER TABLE refunds DROP CONSTRAINT ck_refunds_status;
ALTER TABLE refunds ADD CONSTRAINT ck_refunds_status
    CHECK (status IN ('REQUESTED', 'COMPLETED', 'DECLINED', 'MANUAL_REVIEW'));

ALTER TABLE refunds ADD CONSTRAINT ck_refunds_review_reason
    CHECK (review_reason IN ('LEDGER_REQUEST_NOT_ACCEPTED', 'RETRY_EXHAUSTED'));
ALTER TABLE refunds ADD CONSTRAINT ck_refunds_manual_review_has_reason
    CHECK ((status = 'MANUAL_REVIEW') = (review_reason IS NOT NULL));
