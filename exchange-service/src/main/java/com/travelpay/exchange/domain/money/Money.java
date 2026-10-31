package com.travelpay.exchange.domain.money;

import java.math.BigDecimal;
import java.util.Objects;

import com.travelpay.common.error.BusinessException;

/**
 * 통화와 최소 단위 정수 금액입니다. (KRW 1000원 = 1000, CNY 10.50위안 = 1050)
 * 저장·원장 전달은 최소 단위 정수로 하고, 계산과 표시만 통화 단위 소수로 바꿉니다.
 *
 * @param minorUnits 최소 단위 금액. 0 이상입니다.
 */
public record Money(Currency currency, long minorUnits) {

    /** 통화가 없거나 금액이 음수면 만들 수 없습니다. */
    public Money {
        Objects.requireNonNull(currency, "currency");
        if (minorUnits < 0) {
            throw new IllegalArgumentException("minorUnits must not be negative");
        }
    }

    /**
     * 통화 단위 금액(예: 10.50)을 최소 단위로 바꿉니다.
     * 음수이거나, 통화 자릿수를 넘거나(JPY 100.5), 범위를 넘으면 EXCHANGE_INVALID_AMOUNT 오류입니다.
     */
    public static Money of(Currency currency, BigDecimal amount) {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0 || amount.stripTrailingZeros().scale() > currency.fractionDigits()) {
            throw new BusinessException(MoneyErrorCode.INVALID_AMOUNT);
        }
        try {
            return new Money(currency, amount.movePointRight(currency.fractionDigits()).longValueExact());
        } catch (ArithmeticException e) {
            throw new BusinessException(MoneyErrorCode.INVALID_AMOUNT);
        }
    }

    /** 통화 단위 금액입니다. CNY 1050이면 10.50입니다. */
    public BigDecimal amount() {
        return BigDecimal.valueOf(minorUnits, currency.fractionDigits());
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(currency, Math.addExact(minorUnits, other.minorUnits));
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return minorUnits < other.minorUnits;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return minorUnits > other.minorUnits;
    }

    /** 통화가 다른 금액끼리는 계산하지 않습니다. KRW와 JPY를 더하는 실수를 막습니다. */
    private void requireSameCurrency(Money other) {
        if (currency != Objects.requireNonNull(other, "other").currency) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
