package com.travelpay.exchange.presentation.limit;

import com.travelpay.exchange.domain.quote.AmountRange;

/**
 * 보내는 통화별 1회 환전 한도 응답입니다. 금액은 견적 응답과 같이 통화 단위 문자열로 내려줍니다. (예: CNY "10.00")
 *
 * @param currency  보내는 통화
 * @param minAmount 1회 최소 금액 (포함)
 * @param maxAmount 1회 최대 금액 (포함)
 */
public record ExchangeLimitResponse(
        String currency,
        String minAmount,
        String maxAmount
) {

    public static ExchangeLimitResponse from(AmountRange range) {
        return new ExchangeLimitResponse(
                range.min().currency().name(),
                range.min().amount().toPlainString(),
                range.max().amount().toPlainString()
        );
    }
}
