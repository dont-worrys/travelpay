package com.travelpay.exchange.domain.ledger;

/**
 * 원장 거래 등록입니다. 환전은 이 인터페이스에만 의존하고, 원장 호출 방법은 infrastructure 구현체가 정합니다.
 * 구현체는 예외를 던지지 않고 모든 결과를 {@link LedgerPostResult}로 돌려줍니다.
 */
public interface LedgerClient {

    LedgerPostResult post(LedgerPosting posting);
}
