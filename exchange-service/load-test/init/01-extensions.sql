-- 측정 DB가 처음 만들어질 때 한 번 실행됩니다.
-- pg_stat_statements: 쿼리 모양별 실행 횟수·시간을 모읍니다. (서버 설정 shared_preload_libraries와 함께 필요)
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
