-- 가맹점 원장 계정. 원장의 지갑 계정 생성 API를 가맹점 ID(walletId)로 호출해 만듭니다.
-- 원장 호출 전에는 PENDING으로 저장하고, 계정이 생기면 ledger_wallet_id를 채워 ACTIVE로 바꿉니다.
ALTER TABLE merchants
    ADD COLUMN ledger_wallet_id UUID;

ALTER TABLE merchants
    DROP CONSTRAINT ck_merchants_status,
    ADD CONSTRAINT ck_merchants_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED'));

-- 가맹점 주인. 가맹점 생성 시 관리자가 지정한 회원 ID로, 주인만 자기 가맹점 정산을 조회할 수 있게 합니다.
-- 한 회원이 여러 가맹점을 가질 수 있어 UNIQUE 없이 인덱스만 둡니다.
-- 기존 가맹점이 있으면 NOT NULL 추가가 실패합니다. 기존 데이터는 각 환경에서 직접 정리합니다.
ALTER TABLE merchants
    ADD COLUMN owner_user_id UUID NOT NULL;

CREATE INDEX idx_merchants_owner_user_id ON merchants (owner_user_id);
