package com.travelpay.exchange.application.quote;

import com.travelpay.exchange.domain.quote.ExchangeQuote;

/**
 * 조회 시각 기준으로 만료 여부를 함께 담은 견적입니다.
 *
 * @param expired 조회 시각에 만료됐는지. DB 상태는 ISSUED로 남아 있습니다.
 */
public record ExchangeQuoteView(ExchangeQuote quote, boolean expired) {
}
