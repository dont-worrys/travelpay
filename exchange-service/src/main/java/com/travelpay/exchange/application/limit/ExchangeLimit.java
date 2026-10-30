package com.travelpay.exchange.application.limit;

import java.util.Objects;

import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.quote.AmountRange;

/**
 * 보내는 통화 하나의 환전 한도와 잔액 기준 환전 가능 금액입니다.
 * 지갑 잔액을 가져오지 못하면 balance와 availableAmount는 null이고, 1회 한도만 의미가 있습니다.
 *
 * @param range           1회 최소·최대 금액
 * @param balance         지갑 잔액. 지갑이나 그 통화 계정이 없으면 0입니다.
 * @param availableAmount 수수료까지 잔액으로 낼 수 있는 1회 최대 금액. 보낼 수 없으면 0입니다.
 */
public record ExchangeLimit(AmountRange range, Money balance, Money availableAmount) {

    public ExchangeLimit {
        Objects.requireNonNull(range, "range");
    }

    /** 지갑 잔액을 가져오지 못했을 때입니다. */
    public static ExchangeLimit withoutBalance(AmountRange range) {
        return new ExchangeLimit(range, null, null);
    }
}
