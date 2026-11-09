-- 정산도 결제·환불과 같은 복구 정책을 따릅니다: 결과 모름 5회 또는 원장이 요청을 받아들이지 않으면 MANUAL_REVIEW.
-- MANUAL_REVIEW 정산은 항목을 지우지 않습니다(원장에 반영됐을 수 있어 같은 결제가 다시 정산·환불되면 안 됨).
ALTER TABLE settlements DROP CONSTRAINT ck_settlements_status;
ALTER TABLE settlements
    ADD CONSTRAINT ck_settlements_status CHECK (status IN ('REQUESTED', 'PAID', 'FAILED', 'MANUAL_REVIEW'));

ALTER TABLE settlements ADD COLUMN review_reason VARCHAR(30);
ALTER TABLE settlements
    ADD CONSTRAINT ck_settlements_review_reason
        CHECK (review_reason IN ('LEDGER_REQUEST_NOT_ACCEPTED', 'RETRY_EXHAUSTED'));
ALTER TABLE settlements
    ADD CONSTRAINT ck_settlements_manual_review_has_reason
        CHECK ((status = 'MANUAL_REVIEW') = (review_reason IS NOT NULL));

-- 운영자 처리 목록(오래된 순)과 건수
CREATE INDEX ix_settlements_manual_review_created_at_id ON settlements (created_at, id) WHERE status = 'MANUAL_REVIEW';

-- MANUAL_REVIEW 정산을 운영자(MASTER)가 처리한 기록. 상태가 바뀐 처리만 남깁니다.
CREATE TABLE settlement_review_resolutions (
    id                    UUID         NOT NULL,
    settlement_id         UUID         NOT NULL,
    review_reason         VARCHAR(30)  NOT NULL,  -- 멈췄던 사유
    ledger_status         VARCHAR(20)  NOT NULL,  -- 처리 근거가 된 원장 조회 결과
    ledger_transaction_id UUID,
    to_status             VARCHAR(20)  NOT NULL,
    actor_id              UUID         NOT NULL,  -- 처리한 MASTER
    description           VARCHAR(255) NOT NULL,  -- 시스템이 남기는 처리 설명
    memo                  VARCHAR(255),           -- 운영자 메모(선택)
    created_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_settlement_review_resolutions PRIMARY KEY (id),
    CONSTRAINT fk_settlement_review_resolutions_settlement_id FOREIGN KEY (settlement_id) REFERENCES settlements (id),
    CONSTRAINT ck_settlement_review_resolutions_review_reason
        CHECK (review_reason IN ('LEDGER_REQUEST_NOT_ACCEPTED', 'RETRY_EXHAUSTED')),
    CONSTRAINT ck_settlement_review_resolutions_ledger_status
        CHECK (ledger_status IN ('POSTED', 'REJECTED', 'NOT_FOUND')),
    CONSTRAINT ck_settlement_review_resolutions_ledger_transaction_id
        CHECK ((ledger_status = 'NOT_FOUND') = (ledger_transaction_id IS NULL)),
    CONSTRAINT ck_settlement_review_resolutions_to_status
        CHECK (to_status IN ('PAID', 'FAILED'))
);

-- 정산별 처리 기록을 시간순으로 조회합니다.
CREATE INDEX ix_settlement_review_resolutions_settlement_id_created_at
    ON settlement_review_resolutions (settlement_id, created_at);
