package com.travelpay.exchange.presentation.rate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.travelpay.exchange.domain.rate.ExchangeRate;

/**
 * 통화별 환율 응답입니다. 환율 값은 unit 단위 외화의 원화 가격입니다. (예: JPY unit=100이면 100엔당 가격)
 * 환율은 부동소수점 오차 없이 전달하도록 문자열로 내려줍니다.
 *
 * @param buyRate  고객이 외화를 살 때 환율
 * @param sellRate 고객이 외화를 팔 때 환율
 */
public record ExchangeRateResponse(
        String currency,
        String priceCurrency,
        int unit,
        String midRate,
        String buyRate,
        String sellRate,
        String source,
        LocalDate rateDate,
        Instant collectedAt
) {

    public static ExchangeRateResponse from(ExchangeRate rate) {
        return new ExchangeRateResponse(
                rate.getCurrency().name(),
                rate.getPriceCurrency(),
                rate.getUnit(),
                plain(rate.getMidRate()),
                plain(rate.getBuyRate()),
                plain(rate.getSellRate()),
                rate.getSource().name(),
                rate.getRateDate(),
                rate.getCollectedAt()
        );
    }

    /** DB의 NUMERIC(18,8) 자릿수(838.30000000)를 정리해 838.3처럼 표기합니다. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
