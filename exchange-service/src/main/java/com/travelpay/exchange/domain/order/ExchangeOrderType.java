package com.travelpay.exchange.domain.order;

import java.util.Objects;

import com.travelpay.exchange.domain.money.Currency;

/** 환전 주문 유형입니다. 주문의 한쪽은 항상 원화라, 원화가 어느 쪽에 있는지로 정해집니다. 저장하지 않고 통화로 판단합니다. */
public enum ExchangeOrderType {
    /** 원화 → 외화 */
    EXCHANGE,
    /** 외화 → 원화 */
    RE_EXCHANGE;

    /** 받는 통화가 원화면 재환전, 아니면 환전입니다. */
    public static ExchangeOrderType of(Currency toCurrency) {
        return Objects.requireNonNull(toCurrency, "toCurrency") == Currency.KRW ? RE_EXCHANGE : EXCHANGE;
    }
}
