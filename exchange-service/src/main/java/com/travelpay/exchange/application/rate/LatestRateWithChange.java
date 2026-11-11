package com.travelpay.exchange.application.rate;

import java.util.Objects;

import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.RateChange;

/**
 * 최신 환율과 전일 대비 변동입니다(#272).
 *
 * @param rate   최신 환율
 * @param change 전일 대비. 전일 환율이 없거나 읽지 못했으면 null
 */
public record LatestRateWithChange(ExchangeRate rate, RateChange change) {

    public LatestRateWithChange {
        Objects.requireNonNull(rate, "rate");
    }
}
