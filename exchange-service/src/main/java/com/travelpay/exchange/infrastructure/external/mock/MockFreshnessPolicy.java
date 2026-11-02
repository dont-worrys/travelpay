package com.travelpay.exchange.infrastructure.external.mock;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import com.travelpay.exchange.domain.rate.RateFreshnessPolicy;

/**
 * 가짜 환율은 고시일이 아니라 수집 시각으로 최신인지 판단합니다. 하루 종일 고시일이 같아 고시일로는 구분할 수 없습니다.
 * 수집이 멈추면 잠시 뒤 견적 발급이 막혀, 오래된 환율로 견적이 나가지 않습니다.
 */
public class MockFreshnessPolicy implements RateFreshnessPolicy {

    private final Duration staleAfter;

    /** @param staleAfter 마지막 수집이 이 시간보다 오래되면 최신이 아닙니다. 0보다 커야 합니다 */
    public MockFreshnessPolicy(Duration staleAfter) {
        Objects.requireNonNull(staleAfter, "staleAfter");
        if (staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        this.staleAfter = staleAfter;
    }

    /** 수집 시각이 지금으로부터 staleAfter 이내면 최신입니다. 정확히 staleAfter 전이면 아직 최신으로 봅니다. */
    @Override
    public boolean isFresh(LocalDate rateDate, Instant collectedAt, Instant now) {
        Objects.requireNonNull(collectedAt, "collectedAt");
        Objects.requireNonNull(now, "now");
        return !collectedAt.isBefore(now.minus(staleAfter));
    }
}
