package com.travelpay.wallet.domain.resolution;

/** 같은 멱등 키의 보정이 동시에 먼저 저장된 경우. 호출자는 롤백 후 키로 다시 읽어 응답한다 */
public class DuplicateChargeResolutionException extends RuntimeException {

    public DuplicateChargeResolutionException(Throwable cause) {
        super("같은 멱등 키의 보정이 이미 저장되었습니다.", cause);
    }
}
