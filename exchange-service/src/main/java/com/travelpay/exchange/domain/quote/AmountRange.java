package com.travelpay.exchange.domain.quote;

import java.util.Objects;

import com.travelpay.exchange.domain.money.Money;

/** 한 번에 보낼 수 있는 금액 범위입니다. 최소·최대 금액을 포함합니다. */
public record AmountRange(Money min, Money max) {

    public AmountRange {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.currency() != max.currency()) {
            throw new IllegalArgumentException("min and max must have the same currency");
        }
        if (min.isZero() || min.isGreaterThan(max)) {
            throw new IllegalArgumentException("range must satisfy 0 < min <= max: " + min + ", " + max);
        }
    }

    public boolean contains(Money amount) {
        return !amount.isLessThan(min) && !amount.isGreaterThan(max);
    }
}
