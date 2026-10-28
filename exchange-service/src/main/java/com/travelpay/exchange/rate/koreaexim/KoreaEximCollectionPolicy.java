package com.travelpay.exchange.rate.koreaexim;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

import com.travelpay.exchange.rate.policy.RateCollectionPolicy;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;

/**
 * 평일 고시 시간대에만 주기적으로 수집을 시도하고, 그날 고시값이 저장되면 그날은 더 수집하지 않습니다.
 */
public class KoreaEximCollectionPolicy implements RateCollectionPolicy {

    private final CronTrigger trigger;

    public KoreaEximCollectionPolicy(String cron, ZoneId zone) {
        this.trigger = new CronTrigger(Objects.requireNonNull(cron, "cron"), Objects.requireNonNull(zone, "zone"));
    }

    @Override
    public Trigger trigger() {
        return trigger;
    }

    @Override
    public boolean shouldCollect(LocalDate today, Optional<LocalDate> latestRateDate) {
        Objects.requireNonNull(today, "today");
        return latestRateDate.map(latest -> latest.isBefore(today)).orElse(true);
    }

    @Override
    public boolean publishesOncePerDay() {
        return true;
    }
}
