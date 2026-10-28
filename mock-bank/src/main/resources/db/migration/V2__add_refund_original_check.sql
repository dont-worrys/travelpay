-- 성공한 환불은 원거래가 반드시 있어야 함
ALTER TABLE bank_transactions
    ADD CONSTRAINT ck_bank_transactions_refund_original
        CHECK (type <> 'REFUND' OR status <> 'SUCCEEDED' OR original_tx_id IS NOT NULL);
