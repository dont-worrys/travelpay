-- 원장이 요청을 받지 않은 송금(review_reason LEDGER_REQUEST_NOT_ACCEPTED)을 운영자가 FAILED로 확정할 때의 실패 사유를 추가합니다.
-- 원장의 거절 기록은 이전에 들어온 다른 요청의 것일 수 있어 그 거절 사유를 쓰지 않고, 송금 쪽에서 NOT_PROCESSED로 정합니다.
ALTER TABLE transfer DROP CONSTRAINT ck_transfer_failure_reason;
ALTER TABLE transfer ADD CONSTRAINT ck_transfer_failure_reason
    CHECK (failure_reason IN ('INSUFFICIENT_BALANCE', 'ACCOUNT_UNAVAILABLE', 'NOT_PROCESSED'));
