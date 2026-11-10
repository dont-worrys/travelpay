-- 예약 환전(#240) 취소 사유. 사용자 취소 말고 시스템이 체결 중에 취소하는 경우(지갑 해지, 체결 시점 한도 밖)를
-- 사용자가 구분할 수 있게 한다. 취소된 예약만 사유가 있다.
ALTER TABLE exchange_reservations ADD COLUMN cancel_reason VARCHAR(30) NULL;

-- 이 컬럼 전의 취소는 모두 사용자 취소였다
UPDATE exchange_reservations SET cancel_reason = 'USER_REQUESTED' WHERE status = 'CANCELED';

ALTER TABLE exchange_reservations
    ADD CONSTRAINT ck_exchange_reservations_cancel_reason CHECK (
        (status = 'CANCELED') = (cancel_reason IS NOT NULL)
        AND (cancel_reason IS NULL OR cancel_reason IN ('USER_REQUESTED', 'WALLET_UNAVAILABLE', 'EXECUTION_REJECTED'))
    );

COMMENT ON COLUMN exchange_reservations.cancel_reason IS
    'CANCELED만. USER_REQUESTED(사용자 취소), WALLET_UNAVAILABLE(체결 시 지갑 없음·사용 불가), '
    'EXECUTION_REJECTED(체결 시점 규칙으로 주문 불가: 한도 밖 등)';
