package com.travelpay.exchange.domain.quote;

/**
 * 견적 조회 응답의 상태입니다. 견적에 저장하지 않고 조회할 때 계산합니다.
 * 사용 여부는 주문(UNIQUE(quote_id))으로, 만료는 만료 시각으로 판단합니다.
 *
 * @see ExchangeQuote#isExpired(java.time.Instant)
 */
public enum QuoteStatus {
    /** 아직 주문에 쓰지 않았고 만료 전. 주문에 쓸 수 있습니다. */
    ISSUED,
    /** 주문에 사용됨. 시간이 지나도 USED입니다. */
    USED,
    /** 주문에 쓰지 않은 채 만료 시각이 지남 */
    EXPIRED
}
