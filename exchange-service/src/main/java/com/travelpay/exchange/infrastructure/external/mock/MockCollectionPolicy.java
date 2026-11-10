package com.travelpay.exchange.infrastructure.external.mock;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;

import com.travelpay.exchange.application.rate.RateCollectionPolicy;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.PeriodicTrigger;

/**
 * 정해진 간격마다 항상 수집합니다. 하루 1건 제한이 없어 수집할 때마다 새 행이 쌓입니다.
 * 앞 수집이 끝난 뒤 간격만큼 쉬고 다시 수집합니다(fixed delay). DB가 느려 수집이 늦어져도
 * 밀린 수집을 몰아서 하지 않아, 부하 측정 중에도 수집이 한꺼번에 몰리지 않습니다.
 */
public class MockCollectionPolicy implements RateCollectionPolicy {

    private final PeriodicTrigger trigger;

    /** @param interval 수집 간격. 0보다 커야 합니다 */
    public MockCollectionPolicy(Duration interval) {
        Objects.requireNonNull(interval, "interval");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        this.trigger = new PeriodicTrigger(interval);
    }

    @Override
    public Trigger trigger() {
        return trigger;
    }

    /** 가짜 환율은 언제나 새 값이 있으므로 항상 수집합니다. */
    @Override
    public boolean shouldCollect(LocalDate today, LocalDate latestRateDate) {
        return true;
    }

    @Override
    public boolean publishesOncePerDay() {
        return false;
    }
}
