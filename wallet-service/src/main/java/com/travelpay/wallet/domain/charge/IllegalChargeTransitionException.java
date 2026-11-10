package com.travelpay.wallet.domain.charge;

/** 허용 전이표(ChargeStatus.nextStatuses)에 없는 전이. 코드 버그이므로 재시도하지 않는다 */
public class IllegalChargeTransitionException extends RuntimeException {

    public IllegalChargeTransitionException(ChargeStatus from, ChargeStatus to) {
        super("허용되지 않은 충전 상태 전이입니다: " + from + " -> " + to);
    }
}
