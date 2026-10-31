-- user-service: src/main/resources/db/migration/V1__create_user_tables.sql
-- 스키마: user_service
-- 엔티티(User·UserCredentials·TermsDocument·UserConsent)와 같은 구조여야 합니다.
-- 앱은 ddl-auto: validate로 검사만 하고, FlywayMigrationTest가 엔티티 기준 스키마와 비교합니다.
-- 기본키·CHECK는 PostgreSQL 기본 이름(<표>_pkey, <표>_<열>_check)을 씁니다.
-- DB 테스트가 도는 Hibernate 생성 스키마는 이 둘의 이름을 정할 수 없어서, 이름까지 같게 맞춥니다.

-- =========================================================
-- users: 로그인 수단과 무관한 회원 공통 정보
-- =========================================================
CREATE TABLE users (
    id                 UUID          NOT NULL,
    real_name          VARCHAR(40)   NOT NULL,
    status             VARCHAR(20)   NOT NULL,
    role               VARCHAR(20)   NOT NULL,
    phone              VARCHAR(20)   NOT NULL,
    phone_verified_at  TIMESTAMPTZ   NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,

    CONSTRAINT users_pkey PRIMARY KEY (id),
    -- 이름이 바뀌면 UserRepositoryImpl이 휴대폰 번호 중복(409)을 구분하지 못합니다.
    CONSTRAINT uk_users_phone UNIQUE (phone),
    CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT users_role_check CHECK (role IN ('USER', 'MASTER'))
);

-- =========================================================
-- user_credentials: 회원의 이메일·비밀번호 로그인 수단 (회원당 최대 1개)
-- =========================================================
CREATE TABLE user_credentials (
    user_id              UUID          NOT NULL,
    login_email          VARCHAR(254)  NOT NULL,
    password_hash        VARCHAR(255)  NOT NULL,
    password_changed_at  TIMESTAMPTZ   NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL,

    CONSTRAINT user_credentials_pkey PRIMARY KEY (user_id),
    -- 이름이 바뀌면 UserCredentialsRepositoryImpl이 이메일 중복(409)을 구분하지 못합니다.
    CONSTRAINT uk_user_credentials_login_email UNIQUE (login_email),
    CONSTRAINT fk_user_credentials_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- =========================================================
-- terms_documents: 게시한 약관 원본, 내용 변경은 새 버전으로 추가
-- =========================================================
CREATE TABLE terms_documents (
    terms_code     VARCHAR(40)   NOT NULL,
    terms_version  VARCHAR(40)   NOT NULL,
    title          VARCHAR(200)  NOT NULL,
    content        TEXT          NOT NULL,
    is_required    BOOLEAN       NOT NULL,
    effective_at   TIMESTAMPTZ   NOT NULL,

    CONSTRAINT terms_documents_pkey PRIMARY KEY (terms_code, terms_version),
    CONSTRAINT uk_terms_documents_code_effective_at UNIQUE (terms_code, effective_at)
);

-- =========================================================
-- user_consents: 회원이 동의한 약관 버전과 시각
-- =========================================================
CREATE TABLE user_consents (
    user_id        UUID          NOT NULL,
    terms_code     VARCHAR(40)   NOT NULL,
    terms_version  VARCHAR(40)   NOT NULL,
    agreed_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT user_consents_pkey PRIMARY KEY (user_id, terms_code, terms_version),
    CONSTRAINT fk_user_consents_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_consents_terms_document
        FOREIGN KEY (terms_code, terms_version) REFERENCES terms_documents (terms_code, terms_version)
);
