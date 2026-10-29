package com.travelpay.wallet.domain.charge;

/** 같은 (userId, idempotencyKey) 충전이 동시에 먼저 저장된 경우. 호출자는 기존 충전을 다시 읽어 응답한다. */
public class DuplicateChargeRequestException extends RuntimeException {

    public DuplicateChargeRequestException(Throwable cause) {
        super("같은 멱등 키의 충전이 이미 저장되었습니다.", cause);
    }
}
