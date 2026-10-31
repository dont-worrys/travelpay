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

    /** TTL은 양수이고, 모든 통화에 그 통화 단위의 1회 한도가 있어야 합니다. 한도 맵은 복사해 바뀌지 않게 둡니다. */
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

    /**
     * 잔액으로 한 번에 보낼 수 있는 최대 금액입니다. 수수료까지 잔액으로 낼 수 있는 가장 큰 금액을 1회 최대로 자릅니다.
     * 그 값이 1회 최소보다 작으면 보낼 수 없으므로 0입니다.
     */
    public Money availableAmount(Money balance) {
        AmountRange range = limits.get(balance.currency());
        Money affordable = feeRate.maxAmountWithin(balance);
        if (affordable.isLessThan(range.min())) {
            return new Money(balance.currency(), 0);
        }
        return affordable.isGreaterThan(range.max()) ? range.max() : affordable;
    }
}
