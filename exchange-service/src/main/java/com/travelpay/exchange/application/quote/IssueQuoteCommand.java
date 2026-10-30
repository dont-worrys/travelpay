package com.travelpay.exchange.application.quote;

import java.math.BigDecimal;
import java.util.UUID;

import com.travelpay.exchange.domain.money.Currency;

/**
 * 사용자 견적 발급 요청입니다.
 *
 * @param amount 보내는 금액 (통화 단위, 예: CNY 100.50)
 */
public record IssueQuoteCommand(UUID userId, Currency fromCurrency, Currency toCurrency, BigDecimal amount) {
}
