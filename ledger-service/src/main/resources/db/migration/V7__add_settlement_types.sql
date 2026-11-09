-- 정산 참조 유형과 거래 항목 유형 추가 (결제 담당 요청)

-- reference_type: 기존 값에 SETTLEMENT 추가
-- 기존 제약은 검증된 상태이고 새 목록은 기존 값을 모두 포함하므로 바로 검증해도 기존 행이 통과한다
ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_reference_type;
ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_reference_type
        CHECK (reference_type IN ('CHARGE', 'PAYMENT', 'PAYMENT_REFUND', 'TRANSFER', 'EXCHANGE', 'SETTLEMENT'));

-- entry_type: 기존 11개에 SETTLEMENT_OUT, SETTLEMENT_IN 추가
-- 팀원 로컬 DB 확인 전이라 기존과 같이 NOT VALID 유지 (새로 들어오는 행만 검사)
ALTER TABLE ledger_entries DROP CONSTRAINT ck_ledger_entries_entry_type;
ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_entries_entry_type
        CHECK (entry_type IN (
                              'CHARGE_OUT', 'CHARGE_IN',
                              'PAYMENT_OUT', 'PAYMENT_IN',
                              'PAYMENT_REFUND_OUT', 'PAYMENT_REFUND_IN',
                              'EXCHANGE_OUT', 'EXCHANGE_IN', 'EXCHANGE_FEE',
                              'TRANSFER_OUT', 'TRANSFER_IN',
                              'SETTLEMENT_OUT', 'SETTLEMENT_IN'
            )) NOT VALID;