package com.travelpay.exchange.domain.ledger;

import java.util.UUID;

/**
 * 원장 거래 등록·조회입니다. 환전은 이 인터페이스에만 의존하고, 원장 호출 방법은 infrastructure 구현체가 정합니다.
 * 구현체는 예외를 던지지 않고 모든 결과를 {@link LedgerPostResult}·{@link LedgerLookupResult}로 돌려줍니다.
 */
public interface LedgerClient {

    LedgerPostResult post(LedgerPosting posting);

    /** 주문 ID(referenceType=EXCHANGE, referenceId=orderId)로 원장 거래를 단건 조회합니다. */
    LedgerLookupResult findByOrderId(UUID orderId);
}
