package com.travelpay.exchange.domain.quote;

/**
 * 저장하는 견적 상태입니다. 만료는 상태로 저장하지 않고 만료 시각으로 판단합니다.
 *
 * @see ExchangeQuote#isExpired(java.time.Instant)
 */
public enum QuoteStatus {
    /** 발급됨. 만료 전이면 주문에 쓸 수 있습니다. */
    ISSUED,
    /** 주문에 사용됨 */
    USED
}
