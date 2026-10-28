package com.travelpay.exchange.infrastructure.external.koreaexim;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

import com.travelpay.exchange.application.rate.RateCollectionPolicy;
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

    /** 오늘 고시분이 아직 저장되지 않았으면 수집합니다. */
    @Override
    public boolean shouldCollect(LocalDate today, LocalDate latestRateDate) {
        Objects.requireNonNull(today, "today");
        return Objects.requireNonNull(latestRateDate, "latestRateDate").isBefore(today);
    }

    @Override
    public boolean publishesOncePerDay() {
        return true;
    }
}
