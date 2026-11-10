-- 측정 DB를 처음 만들 때 한 번 실행됩니다(공용 01-service-schemas.sql 다음).
-- pg_stat_statements: 쿼리 모양별 실행 횟수·시간을 모읍니다. (서버 설정 shared_preload_libraries와 함께 필요)
-- 공용 init이 public 스키마 권한을 PUBLIC에서 회수하므로 조회는 관리자 계정(travelpay_admin)으로 합니다.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
