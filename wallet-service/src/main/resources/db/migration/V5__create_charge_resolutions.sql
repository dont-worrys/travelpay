-- wallet-service: src/main/resources/db/migration/V5__create_charge_resolutions.sql
-- 스키마: wallet_service
-- 충전 보정: MANUAL_REVIEW 충전을 운영자가 확정·재개한 기록
-- 증거는 보정 실행 순간 서버가 다시 조회한 은행 출금·환불·원장 사실이다 (계좌번호·응답 원문은 저장하지 않음)

-- =========================================================
-- charge_resolutions: 보정 요청 1건 = 1행 (성공한 보정만 저장)
-- =========================================================
CREATE TABLE charge_resolutions (
    id                        UUID          NOT NULL,
    idempotency_key           UUID          NOT NULL,
    charge_id                 UUID          NOT NULL,
    operator_id               UUID          NOT NULL,
    action                    VARCHAR(30)   NOT NULL,
    reason                    VARCHAR(500)  NOT NULL,
    -- 은행 출금 (FOUND·NOT_FOUND)
    withdrawal_lookup         VARCHAR(20)   NOT NULL,
    withdrawal_tx_id          UUID          NULL,
    withdrawal_status         VARCHAR(20)   NULL,
    withdrawal_amount         BIGINT        NULL,
    -- 은행 환불
    refund_lookup             VARCHAR(20)   NOT NULL,
    refund_tx_id              UUID          NULL,
    refund_status             VARCHAR(20)   NULL,
    refund_amount             BIGINT        NULL,
    -- 원장 (금액은 충전 지갑 적립 항목)
    ledger_lookup             VARCHAR(20)   NOT NULL,
    ledger_tx_id              UUID          NULL,
    ledger_status             VARCHAR(20)   NULL,
    ledger_amount             BIGINT        NULL,
    result_status             VARCHAR(30)   NOT NULL,
    reconciliation_result_id  UUID          NULL,
    created_at                TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_charge_resolutions PRIMARY KEY (id),
    CONSTRAINT uk_charge_resolutions_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_charge_resolutions_charge
        FOREIGN KEY (charge_id) REFERENCES charge_requests (id),
    CONSTRAINT fk_charge_resolutions_reconciliation_result
        FOREIGN KEY (reconciliation_result_id) REFERENCES reconciliation_results (id),
    CONSTRAINT ck_charge_resolutions_action CHECK (action IN (
        'CONFIRM_COMPLETED', 'CONFIRM_FAILED', 'CONFIRM_REFUNDED', 'RESUME_CREDIT', 'RESUME_REFUND')),
    CONSTRAINT ck_charge_resolutions_result_status CHECK (result_status IN (
        'COMPLETED', 'FAILED', 'REFUNDED', 'CREDIT_PROCESSING', 'REFUND_PROCESSING')),
    CONSTRAINT ck_charge_resolutions_withdrawal_lookup CHECK (withdrawal_lookup IN ('FOUND', 'NOT_FOUND')),
    CONSTRAINT ck_charge_resolutions_refund_lookup CHECK (refund_lookup IN ('FOUND', 'NOT_FOUND')),
    CONSTRAINT ck_charge_resolutions_ledger_lookup CHECK (ledger_lookup IN ('FOUND', 'NOT_FOUND'))
);

-- 충전별 보정 내역
CREATE INDEX ix_charge_resolutions_charge ON charge_resolutions (charge_id, created_at);

-- 상태 이력: 보정으로 바뀐 줄만 보정 ID를 가진다
ALTER TABLE charge_status_history ADD COLUMN resolution_id UUID NULL;
ALTER TABLE charge_status_history
    ADD CONSTRAINT fk_charge_status_history_resolution
        FOREIGN KEY (resolution_id) REFERENCES charge_resolutions (id);

-- 대사 결과: 판정(mismatch_type·snapshot)은 그대로 두고 처리한 보정만 연결한다
ALTER TABLE reconciliation_results ADD COLUMN resolution_id UUID NULL;
ALTER TABLE reconciliation_results
    ADD CONSTRAINT fk_reconciliation_results_resolution
        FOREIGN KEY (resolution_id) REFERENCES charge_resolutions (id);
ALTER TABLE reconciliation_results
    ADD CONSTRAINT ck_reconciliation_results_resolution_status CHECK (resolution_status IN ('PENDING', 'RESOLVED'));
ALTER TABLE reconciliation_results
    ADD CONSTRAINT ck_reconciliation_results_resolution_link CHECK (
        (resolution_status = 'RESOLVED') = (resolution_id IS NOT NULL));
