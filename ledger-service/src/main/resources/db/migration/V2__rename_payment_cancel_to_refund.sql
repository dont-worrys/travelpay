-- 결제 취소 → 결제 환불로 참조 유형 이름 변경
ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_reference_type;
ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_reference_type
        CHECK (reference_type IN ('CHARGE', 'PAYMENT', 'PAYMENT_REFUND', 'TRANSFER', 'EXCHANGE'));