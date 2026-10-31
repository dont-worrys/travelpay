package com.travelpay.exchange.presentation.quote;

import java.math.BigDecimal;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.application.quote.IssueQuoteCommand;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.rate.ExchangeRateErrorCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 견적 발급 요청입니다.
 *
 * @param fromCurrency 보내는 통화 코드 (대소문자 무관)
 * @param toCurrency   받는 통화 코드 (대소문자 무관)
 * @param amount       보내는 금액 (통화 단위, 예: CNY 100.50). 부동소수점 오차가 없도록 문자열로 보내도 됩니다.
 */
public record ExchangeQuoteRequest(
        @NotBlank String fromCurrency,
        @NotBlank String toCurrency,
        @NotNull BigDecimal amount
) {

    /** 지원하지 않는 통화 코드면 EXCHANGE_UNSUPPORTED_CURRENCY 오류입니다. */
    public IssueQuoteCommand toCommand(UUID userId) {
        return new IssueQuoteCommand(userId, currency(fromCurrency), currency(toCurrency), amount);
    }

    private static Currency currency(String code) {
        return Currency.find(code)
                .orElseThrow(() -> new BusinessException(ExchangeRateErrorCode.UNSUPPORTED_CURRENCY));
    }
}
