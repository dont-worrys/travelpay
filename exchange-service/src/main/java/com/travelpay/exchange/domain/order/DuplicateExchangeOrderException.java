package com.travelpay.exchange.domain.order;

/**
 * 주문 UNIQUE 제약(같은 견적, 같은 회원·멱등 키)에 걸려 저장되지 않은 경우입니다.
 * 같은 신청이 먼저 저장됐을 수 있으므로, 신청 쪽에서 새 트랜잭션으로 다시 조회해 원인을 가립니다.
 */
public class DuplicateExchangeOrderException extends RuntimeException {

    public DuplicateExchangeOrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
