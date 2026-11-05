-- 기간 목록 조회(커서)용 인덱스
-- 컬럼 순서: 같음 조건(reference_type) → 범위·커서 조건(created_at) → 같은 시각 보조 정렬 키(id)
-- where reference_type = ? and created_at >= ? and created_at < ? [and (created_at, id) > (?, ?)]
-- order by created_at, id 를 정렬 없이 인덱스 순서로 읽고 fetch first n rows에서 멈춘다
-- CONCURRENTLY: 운영 중 쓰기를 막지 않도록 만든다. 트랜잭션 안에서 실행할 수 없어
-- 같은 이름의 .sql.conf에서 executeInTransaction=false로 이 파일만 트랜잭션 없이 실행한다.
-- IF NOT EXISTS는 쓰지 않는다: 실패로 남은 INVALID 인덱스를 건너뛰고 성공 처리되는 것을 막기 위해
CREATE INDEX CONCURRENTLY ix_ledger_transactions_reference_created
    ON ledger_transactions (reference_type, created_at, id);
