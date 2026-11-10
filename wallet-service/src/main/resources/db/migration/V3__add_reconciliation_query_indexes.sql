-- wallet-service: src/main/resources/db/migration/V3__add_reconciliation_query_indexes.sql
-- 스키마: wallet_service
-- 관리자 대사 조회용 인덱스

-- 실행 목록: started_at·id 내림차순
CREATE INDEX ix_reconciliation_runs_started ON reconciliation_runs (started_at DESC, id DESC);

-- 다음 기간 계산: COMPLETED·PARTIAL 중 period_to가 가장 늦은 실행
CREATE INDEX ix_reconciliation_runs_closed_period_to
    ON reconciliation_runs (period_to DESC, started_at DESC) WHERE status IN ('COMPLETED', 'PARTIAL');

-- 실행 상세 결과: run_id별, detected_at·id 내림차순 (FK run_id 조회도 이 인덱스를 쓴다)
CREATE INDEX ix_reconciliation_results_run_detected
    ON reconciliation_results (run_id, detected_at DESC, id DESC);
