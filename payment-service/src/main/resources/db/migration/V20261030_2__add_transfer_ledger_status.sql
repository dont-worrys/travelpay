-- 원장 거래 등록 연동: 처리 중(PROCESSING)·완료(COMPLETED)·실패(FAILED) 상태와 원장 확정 거절 사유를 추가합니다.
-- 실패 사유는 FAILED 송금에만 있으며, 같은 Idempotency-Key 재요청에 처음과 같은 오류로 응답하는 데 씁니다.
ALTER TABLE transfer ADD COLUMN failure_reason VARCHAR(30);

ALTER TABLE transfer DROP CONSTRAINT ck_transfer_status;
ALTER TABLE transfer ADD CONSTRAINT ck_transfer_status
    CHECK (status IN ('REQUESTED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'));

ALTER TABLE transfer ADD CONSTRAINT ck_transfer_failure_reason
    CHECK (failure_reason IN ('INSUFFICIENT_BALANCE', 'ACCOUNT_UNAVAILABLE'));
ALTER TABLE transfer ADD CONSTRAINT ck_transfer_failed_has_reason
    CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL));
