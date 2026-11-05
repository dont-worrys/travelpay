-- 기간 목록 조회(커서)용 인덱스
-- 컬럼 순서: 같음 조건(reference_type) → 범위·커서 조건(created_at) → 같은 시각 보조 정렬 키(id)
-- where reference_type = ? and created_at >= ? and created_at < ? [and (created_at, id) > (?, ?)]
-- order by created_at, id 를 정렬 없이 인덱스 순서로 읽고 fetch first n rows에서 멈춘다
CREATE INDEX ix_ledger_transactions_reference_created
    ON ledger_transactions (reference_type, created_at, id);
