package com.travelpay.ledger.domain.transaction;

/** 같은 (referenceType, referenceId) 거래가 동시에 먼저 저장된 경우. 호출자는 기존 거래를 다시 읽어 응답한다. */
public class DuplicateReferenceException extends RuntimeException {

    public DuplicateReferenceException(Throwable cause) {
        super("같은 참조의 거래가 이미 저장되었습니다.", cause);
    }
}
