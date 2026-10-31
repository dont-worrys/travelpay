-- 환전 시스템 계정 (환전 분개의 회사 쪽 항목)
-- FX_POSITION: 회사 통화 보유분. EXCHANGE_OUT은 보내는 통화 CREDIT, EXCHANGE_IN은 받는 통화 DEBIT
-- FEE_REVENUE: 수수료 수익. 수수료는 보내는 통화로 받으므로 세 통화 모두 필요 (EXCHANGE_FEE CREDIT)
INSERT INTO ledger_accounts (id, owner_type, wallet_id, currency, account_type, balance, status, created_at, updated_at)
VALUES (gen_random_uuid(), 'SYSTEM', NULL, 'KRW', 'FX_POSITION', 0, 'ACTIVE', now(), now()),
       (gen_random_uuid(), 'SYSTEM', NULL, 'JPY', 'FX_POSITION', 0, 'ACTIVE', now(), now()),
       (gen_random_uuid(), 'SYSTEM', NULL, 'CNY', 'FX_POSITION', 0, 'ACTIVE', now(), now()),
       (gen_random_uuid(), 'SYSTEM', NULL, 'KRW', 'FEE_REVENUE', 0, 'ACTIVE', now(), now()),
       (gen_random_uuid(), 'SYSTEM', NULL, 'JPY', 'FEE_REVENUE', 0, 'ACTIVE', now(), now()),
       (gen_random_uuid(), 'SYSTEM', NULL, 'CNY', 'FEE_REVENUE', 0, 'ACTIVE', now(), now())
    ON CONFLICT (account_type, currency) WHERE owner_type = 'SYSTEM' DO NOTHING;
