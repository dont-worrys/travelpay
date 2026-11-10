package com.travelpay.wallet.infra.kafka;

/** chargeId를 꺼낼 수 없는 충전 이벤트. 다시 실행해도 같으므로 재시도하지 않는다 */
public class InvalidChargeEventException extends RuntimeException {

    public InvalidChargeEventException(Throwable cause) {
        super("충전 이벤트에서 chargeId를 꺼낼 수 없습니다.", cause);
    }
}
