-- 송금 상태 변경 이력. 지금은 운영자의 MANUAL_REVIEW 확정(관리자 보정 API)만 기록합니다.
-- 자동 처리(스케줄러·원장 응답)의 상태 변경도 기록하게 되면 actor_type에 SYSTEM을 추가하고 actor_id를 nullable로 바꿉니다.
-- ledger_transaction_id: 확정 근거로 서버가 원장에서 확인한 거래 ID. 원장 기록이 없어 재처리로 돌린 경우 null입니다.
CREATE TABLE transfer_status_history (
    id                    UUID         NOT NULL,
    transfer_id           UUID         NOT NULL,
    from_status           VARCHAR(20)  NOT NULL,
    to_status             VARCHAR(20)  NOT NULL,
    reason                VARCHAR(255) NOT NULL,
    actor_type            VARCHAR(20)  NOT NULL,
    actor_id              UUID         NOT NULL,
    ledger_transaction_id UUID,
    created_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_transfer_status_history PRIMARY KEY (id),
    CONSTRAINT fk_transfer_status_history_transfer FOREIGN KEY (transfer_id) REFERENCES transfer (id),
    CONSTRAINT ck_transfer_status_history_from_status
        CHECK (from_status IN ('REQUESTED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'MANUAL_REVIEW')),
    CONSTRAINT ck_transfer_status_history_to_status
        CHECK (to_status IN ('REQUESTED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'MANUAL_REVIEW')),
    CONSTRAINT ck_transfer_status_history_actor_type CHECK (actor_type IN ('OPERATOR')),
    CONSTRAINT ck_transfer_status_history_reason CHECK (length(btrim(reason)) > 0)
);

CREATE INDEX ix_transfer_status_history_transfer_id ON transfer_status_history (transfer_id, created_at, id);
