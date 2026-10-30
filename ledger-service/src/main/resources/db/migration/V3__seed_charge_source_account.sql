-- 충전 자금원 시스템 계정 (모든 충전 적립의 DEBIT 쪽)
INSERT INTO ledger_accounts (id, owner_type, wallet_id, currency, account_type, balance, status, created_at, updated_at)
VALUES (gen_random_uuid(), 'SYSTEM', NULL, 'KRW', 'CHARGE_SOURCE', 0, 'ACTIVE', now(), now())
    ON CONFLICT (account_type, currency) WHERE owner_type = 'SYSTEM' DO NOTHING;