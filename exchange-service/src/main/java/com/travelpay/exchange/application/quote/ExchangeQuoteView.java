package com.travelpay.exchange.application.quote;

import java.util.Objects;

import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.QuoteStatus;

/**
 * 견적과 응답 시점의 상태입니다. 상태는 견적에 저장하지 않고 응답할 때 계산합니다.
 */
public record ExchangeQuoteView(ExchangeQuote quote, QuoteStatus status) {

    public ExchangeQuoteView {
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(status, "status");
    }
}
