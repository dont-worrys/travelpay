-- 거래 항목 유형을 도메인별로 합의한 11개 값으로 제한합니다.
-- 팀원 로컬 DB에 이름 변경 전 값이 남아 있을 수 있어 NOT VALID로 추가합니다(새로 들어오는 행만 검사).
ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_entries_entry_type
        CHECK (entry_type IN (
                              'CHARGE_OUT', 'CHARGE_IN',
                              'PAYMENT_OUT', 'PAYMENT_IN',
                              'PAYMENT_REFUND_OUT', 'PAYMENT_REFUND_IN',
                              'EXCHANGE_OUT', 'EXCHANGE_IN', 'EXCHANGE_FEE',
                              'TRANSFER_OUT', 'TRANSFER_IN'
            )) NOT VALID;