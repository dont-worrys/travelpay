package com.travelpay.exchange.presentation.limit;

import com.travelpay.exchange.application.limit.ExchangeLimit;
import com.travelpay.exchange.domain.money.Money;

/**
 * 보내는 통화별 환전 한도 응답입니다. 금액은 견적 응답과 같이 통화 단위 문자열로 내려줍니다. (예: CNY "10.00")
 *
 * @param currency        보내는 통화
 * @param minAmount       1회 최소 금액 (포함)
 * @param maxAmount       1회 최대 금액 (포함)
 * @param balance         지갑 잔액. 지갑 잔액을 가져오지 못하면 null입니다.
 * @param availableAmount 수수료까지 잔액으로 낼 수 있는 1회 최대 금액. 보낼 수 없으면 "0", 지갑 잔액을 가져오지 못하면 null입니다.
 */
public record ExchangeLimitResponse(
        String currency,
        String minAmount,
        String maxAmount,
        String balance,
        String availableAmount
) {

    public static ExchangeLimitResponse from(ExchangeLimit limit) {
        return new ExchangeLimitResponse(
                limit.range().min().currency().name(),
                amountOf(limit.range().min()),
                amountOf(limit.range().max()),
                amountOf(limit.balance()),
                amountOf(limit.availableAmount())
        );
    }

    private static String amountOf(Money money) {
        return money == null ? null : money.amount().toPlainString();
    }
}
