package com.travelpay.exchange.infrastructure.external.koreaexim;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

import com.travelpay.exchange.domain.rate.RateFreshnessPolicy;

/**
 * 하루 한 번 고시되는 환율은 수집 시각이 아니라 고시일로 판단합니다.
 * 공휴일 달력 없이, 고시일이 기준 일수 이내면 연휴 중에도 유효한 환율로 봅니다.
 */
public class KoreaEximFreshnessPolicy implements RateFreshnessPolicy {

    private final int staleAfterDays;
    private final ZoneId zone;

    public KoreaEximFreshnessPolicy(int staleAfterDays, ZoneId zone) {
        if (staleAfterDays <= 0) {
            throw new IllegalArgumentException("staleAfterDays must be positive");
        }
        this.staleAfterDays = staleAfterDays;
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Override
    public boolean isFresh(LocalDate rateDate, Instant collectedAt, Instant now) {
        Objects.requireNonNull(rateDate, "rateDate");
        LocalDate today = LocalDate.ofInstant(Objects.requireNonNull(now, "now"), zone);
        return !rateDate.isBefore(today.minusDays(staleAfterDays));
    }
}
