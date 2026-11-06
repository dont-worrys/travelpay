-- wallet-service: src/main/resources/db/migration/V2__create_reconciliation_tables.sql
-- 스키마: wallet_service
-- 충전 대사: 은행 거래·충전·원장 거래를 기간 단위로 대조한 실행 기록과 어긋남 결과

-- =========================================================
-- reconciliation_runs: 대사 실행 1회 = 1행
-- =========================================================
CREATE TABLE reconciliation_runs (
    id                  UUID          NOT NULL,
    period_from         TIMESTAMPTZ   NOT NULL,   -- 대사 기간 [period_from, period_to)
    period_to           TIMESTAMPTZ   NOT NULL,
    overlap_from        TIMESTAMPTZ   NULL,       -- 이전 기간과 겹쳐 다시 보는 시작 시각 (스케줄러 도입 시 사용)
    status              VARCHAR(20)   NOT NULL,
    started_at          TIMESTAMPTZ   NOT NULL,
    finished_at         TIMESTAMPTZ   NULL,
    target_count        INT           NOT NULL DEFAULT 0,   -- 판정 대상 충전 (처리 중 제외)
    matched_count       INT           NOT NULL DEFAULT 0,   -- 결과가 하나도 없는 충전
    mismatch_count      INT           NOT NULL DEFAULT 0,   -- SOURCE_UNAVAILABLE 외 불일치가 있는 충전 + 고아 결과
    unverifiable_count  INT           NOT NULL DEFAULT 0,   -- SOURCE_UNAVAILABLE이 있는 충전
    failure_reason      VARCHAR(500)  NULL,

    CONSTRAINT pk_reconciliation_runs PRIMARY KEY (id),
    CONSTRAINT ck_reconciliation_runs_status CHECK (status IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_reconciliation_runs_period CHECK (period_from < period_to),
    CONSTRAINT ck_reconciliation_runs_finished CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CONSTRAINT ck_reconciliation_runs_counts CHECK (target_count >= 0 AND matched_count >= 0
        AND mismatch_count >= 0 AND unverifiable_count >= 0)
);

-- 동시에 RUNNING인 실행은 하나만
CREATE UNIQUE INDEX ux_reconciliation_runs_running ON reconciliation_runs ((true)) WHERE status = 'RUNNING';

-- =========================================================
-- reconciliation_results: 어긋남 1건 = 1행 (정상 건은 저장하지 않음)
-- =========================================================
CREATE TABLE reconciliation_results (
    id                 UUID          NOT NULL,
    run_id             UUID          NOT NULL,
    charge_id          UUID          NULL,       -- 고아 결과면 NULL
    source_type        VARCHAR(10)   NULL,       -- 고아 결과만: BANK·LEDGER
    source_key         VARCHAR(100)  NULL,       -- 고아 결과만: 은행 requestId 또는 원장 transactionId
    mismatch_type      VARCHAR(40)   NOT NULL,
    detail             VARCHAR(500)  NOT NULL,
    snapshot           JSONB         NOT NULL,
    detected_at        TIMESTAMPTZ   NOT NULL,
    resolution_status  VARCHAR(20)   NOT NULL DEFAULT 'PENDING',

    CONSTRAINT pk_reconciliation_results PRIMARY KEY (id),
    CONSTRAINT fk_reconciliation_results_run
        FOREIGN KEY (run_id) REFERENCES reconciliation_runs (id),
    CONSTRAINT ck_reconciliation_results_mismatch_type CHECK (mismatch_type IN (
        'BANK_DEBIT_WITHOUT_CREDIT', 'CREDIT_WITHOUT_BANK_DEBIT', 'CREDIT_AND_REFUND_BOTH', 'AMOUNT_MISMATCH',
        'STATUS_MISMATCH', 'BANK_ORPHAN', 'LEDGER_ORPHAN', 'REVIEW_REQUIRED', 'SOURCE_UNAVAILABLE')),
    CONSTRAINT ck_reconciliation_results_source_type CHECK (source_type IN ('BANK', 'LEDGER')),
    -- 충전 결과는 charge_id만, 고아 결과는 source_type·source_key만
    CONSTRAINT ck_reconciliation_results_target CHECK (
        (charge_id IS NOT NULL AND source_type IS NULL AND source_key IS NULL)
        OR (charge_id IS NULL AND source_type IS NOT NULL AND source_key IS NOT NULL))
);

-- 한 실행에서 같은 대상·유형은 1행
CREATE UNIQUE INDEX ux_reconciliation_results_charge
    ON reconciliation_results (run_id, charge_id, mismatch_type) WHERE charge_id IS NOT NULL;
CREATE UNIQUE INDEX ux_reconciliation_results_orphan
    ON reconciliation_results (run_id, source_type, source_key, mismatch_type) WHERE charge_id IS NULL;

-- 대사 후보 수집: 기간 안에 접수된 충전
CREATE INDEX ix_charge_requests_created ON charge_requests (created_at);
