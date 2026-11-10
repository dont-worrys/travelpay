-- wallet-service: src/main/resources/db/migration/V4__add_reconciliation_run_type.sql
-- 스키마: wallet_service
-- 대사 실행 종류: AUTO(기간 계산으로 정한 실행) · MANUAL(기간을 지정한 수동 실행)
-- 다음 기간 계산은 AUTO만 기준으로 삼아, 기간 지정 재대사가 스케줄러 위치를 움직이지 않게 한다

ALTER TABLE reconciliation_runs ADD COLUMN run_type VARCHAR(10);

-- 기존 행(배포 전 로컬 데이터)은 AUTO로 채운 뒤 제약을 건다
UPDATE reconciliation_runs SET run_type = 'AUTO' WHERE run_type IS NULL;

ALTER TABLE reconciliation_runs ALTER COLUMN run_type SET NOT NULL;
ALTER TABLE reconciliation_runs
    ADD CONSTRAINT ck_reconciliation_runs_run_type CHECK (run_type IN ('AUTO', 'MANUAL'));

-- 다음 기간 계산: AUTO 중 COMPLETED·PARTIAL에서 period_to가 가장 늦은 실행
DROP INDEX ix_reconciliation_runs_closed_period_to;
CREATE INDEX ix_reconciliation_runs_closed_period_to
    ON reconciliation_runs (period_to DESC, started_at DESC)
    WHERE status IN ('COMPLETED', 'PARTIAL') AND run_type = 'AUTO';
