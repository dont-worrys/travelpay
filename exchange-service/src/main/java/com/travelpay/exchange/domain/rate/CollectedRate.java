package com.travelpay.exchange.domain.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

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

    /**
     * 필수 값이 비어 있거나, 단위가 통화의 표시 단위와 다르거나, 환율이 양수가 아니면 만들 수 없습니다.
     * 단위가 다르면 환율 해석이 몇 배씩 틀어지므로 저장하지 않습니다.
     */
    public CollectedRate {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(rateDate, "rateDate");
        Objects.requireNonNull(source, "source");
        if (unit != currency.quoteUnit()) {
            throw new IllegalArgumentException(
                    "unit must be " + currency.quoteUnit() + " for " + currency + " but was " + unit);
        }
        requirePositive(midRate, "midRate");
        requirePositive(buyRate, "buyRate");
        requirePositive(sellRate, "sellRate");
    }

    /** 환율 값이 있고 0보다 큰지 확인합니다. */
    private static void requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
