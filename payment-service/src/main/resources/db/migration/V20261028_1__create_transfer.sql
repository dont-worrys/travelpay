-- 송금 접수 기록. 1차 MVP는 원장 연동 전이라 접수(REQUESTED) 상태만 사용합니다.
-- 회원·지갑은 다른 서비스 소유이므로 FK 없이 ID 값만 저장합니다.
CREATE TABLE transfer (
    id               UUID         NOT NULL,
    sender_id        UUID         NOT NULL,
    receiver_id      UUID         NOT NULL,
    source_wallet_id UUID         NOT NULL,
    target_wallet_id UUID         NOT NULL,
    amount           BIGINT       NOT NULL,
    currency         CHAR(3)      NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    memo             VARCHAR(100),
    idempotency_key  VARCHAR(100) NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_transfer PRIMARY KEY (id),
    CONSTRAINT uk_transfer_sender_id_idempotency_key UNIQUE (sender_id, idempotency_key),
    CONSTRAINT ck_transfer_amount CHECK (amount > 0),
    CONSTRAINT ck_transfer_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_transfer_status CHECK (status IN ('REQUESTED')),
    CONSTRAINT ck_transfer_sender_receiver CHECK (sender_id <> receiver_id),
    CONSTRAINT ck_transfer_source_target_wallet CHECK (source_wallet_id <> target_wallet_id)
);

-- 보낸 내역·받은 내역 목록을 생성 시각 내림차순 커서로 조회합니다.
CREATE INDEX ix_transfer_sender_id_created_at ON transfer (sender_id, created_at DESC, id DESC);
CREATE INDEX ix_transfer_receiver_id_created_at ON transfer (receiver_id, created_at DESC, id DESC);
