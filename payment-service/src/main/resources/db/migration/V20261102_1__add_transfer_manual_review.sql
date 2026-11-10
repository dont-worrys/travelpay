-- 송금 복구: 원장 결과 모름 재시도 횟수와 자동 처리 중단(MANUAL_REVIEW)을 추가합니다. 지갑 충전 복구와 같은 방식입니다.
-- attempt_count: 원장 거래 등록 결과를 확인하지 못한 횟수. 재시도 대기 시간(30초 × 2^(attempt_count - 1))과 한도 판단에 씁니다.
-- review_reason: 자동 처리를 멈춘 사유이며 MANUAL_REVIEW 송금에만 있습니다.
--   LEDGER_REQUEST_NOT_ACCEPTED: 원장이 요청을 받아들이지 않음(409·400 등). 재요청해도 같은 결과라 재시도하지 않습니다.
--   RETRY_EXHAUSTED: 결과 모름이 한도에 이름. 원장 반영 여부를 확인해야 합니다.
ALTER TABLE transfer ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE transfer ADD COLUMN review_reason VARCHAR(30);

ALTER TABLE transfer ADD CONSTRAINT ck_transfer_attempt_count CHECK (attempt_count >= 0);

ALTER TABLE transfer DROP CONSTRAINT ck_transfer_status;
ALTER TABLE transfer ADD CONSTRAINT ck_transfer_status
    CHECK (status IN ('REQUESTED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'MANUAL_REVIEW'));

ALTER TABLE transfer ADD CONSTRAINT ck_transfer_review_reason
    CHECK (review_reason IN ('LEDGER_REQUEST_NOT_ACCEPTED', 'RETRY_EXHAUSTED'));
ALTER TABLE transfer ADD CONSTRAINT ck_transfer_manual_review_has_reason
    CHECK ((status = 'MANUAL_REVIEW') = (review_reason IS NOT NULL));

-- 복구 대상 조회(처리 중 송금을 수정 시각 오래된 순으로)용입니다.
CREATE INDEX ix_transfer_processing_updated_at ON transfer (updated_at) WHERE status = 'PROCESSING';
