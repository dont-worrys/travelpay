package com.travelpay.exchange.rate.provider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import com.travelpay.exchange.rate.domain.ForeignCurrency;
import com.travelpay.exchange.rate.domain.RateSource;

/**
 * 외부에서 가져온 환율 한 건입니다.
 * 환율 값은 unit 단위 외화의 원화 가격입니다. (예: JPY는 100엔당 가격)
 *
 * @param buyRate  고객이 외화를 살 때 환율 (TTS)
 * @param sellRate 고객이 외화를 팔 때 환율 (TTB)
 */
public record CollectedRate(
        ForeignCurrency currency,
        int unit,
        BigDecimal midRate,
        BigDecimal buyRate,
        BigDecimal sellRate,
        LocalDate rateDate,
        RateSource source
) {

    public CollectedRate {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(rateDate, "rateDate");
        Objects.requireNonNull(source, "source");
        if (unit <= 0) {
            throw new IllegalArgumentException("unit must be positive");
        }
        requirePositive(midRate, "midRate");
        requirePositive(buyRate, "buyRate");
        requirePositive(sellRate, "sellRate");
    }

    private static void requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
