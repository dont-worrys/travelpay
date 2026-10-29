package com.travelpay.exchange.domain.quote;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.money.MoneyErrorCode;

/**
 * 견적 발급 규칙입니다. 값은 설정(exchange.quote)에서 받습니다.
 *
 * @param ttl     견적 유효 시간
 * @param feeRate 수수료율. 발급할 때의 값을 견적에 저장합니다.
 * @param limits  보내는 통화별 1회 금액 범위. 모든 통화에 있어야 합니다.
 */
public record QuotePolicy(Duration ttl, FeeRate feeRate, Map<Currency, AmountRange> limits) {

    public QuotePolicy {
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(feeRate, "feeRate");
        Objects.requireNonNull(limits, "limits");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        for (Currency currency : Currency.values()) {
            AmountRange range = limits.get(currency);
            if (range == null || range.min().currency() != currency) {
                throw new IllegalArgumentException("amount range for " + currency + " is missing");
            }
        }
        limits = Map.copyOf(limits);
    }

    /** 보내는 금액이 그 통화의 1회 범위 안인지 확인합니다. 범위 밖이면 EXCHANGE_INVALID_AMOUNT 오류입니다. */
    public void checkAmount(Money amount) {
        if (!limits.get(amount.currency()).contains(amount)) {
            throw new BusinessException(MoneyErrorCode.INVALID_AMOUNT);
        }
    }
}
