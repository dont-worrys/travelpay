package com.travelpay.exchange.domain.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import com.travelpay.exchange.domain.money.Money;

/**
 * 환전 수수료율입니다. 0.01이면 1%입니다.
 * 환율은 매매기준율을 그대로 쓰고, 회사 수익은 이 수수료로만 받습니다.
 *
 * @param value 0 이상 1 미만, 소수 6자리 이하 (exchange_quotes.fee_rate NUMERIC(7,6))
 */
public record FeeRate(BigDecimal value) {

    private static final int MAX_SCALE = 6;

    public FeeRate {
        Objects.requireNonNull(value, "value");
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("fee rate must be in [0, 1): " + value);
        }
        if (value.stripTrailingZeros().scale() > MAX_SCALE) {
            throw new IllegalArgumentException("fee rate must have at most " + MAX_SCALE + " decimal places: " + value);
        }
    }

    /**
     * 보내는 금액에 대한 수수료입니다. 보내는 통화로 받고, 통화 자릿수로 올림합니다.
     * 올림이라 수수료율이 0보다 크면 작은 금액에서도 수수료가 0으로 사라지지 않습니다.
     */
    public Money feeFor(Money amount) {
        BigDecimal fee = amount.amount().multiply(value)
                .setScale(amount.currency().fractionDigits(), RoundingMode.UP);
        return Money.of(amount.currency(), fee);
    }
}
