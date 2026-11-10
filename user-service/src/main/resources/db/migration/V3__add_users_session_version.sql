-- user-service: src/main/resources/db/migration/V3__add_users_session_version.sql
-- 스키마: user_service
-- 관리자가 회원의 세션을 끝내거나 회원을 정지할 때 1씩 올리는 값입니다.
-- 로그인할 때 세션에 복사해 두고, 로그아웃을 뺀 로그인이 필요한 요청마다 지금 값과 비교해 다르면 다시 로그인하게 합니다.
-- 기존 회원은 0에서 시작합니다. 이 값이 없던 기존 세션도 0으로 읽혀서 그대로 쓸 수 있습니다.

ALTER TABLE users ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;
