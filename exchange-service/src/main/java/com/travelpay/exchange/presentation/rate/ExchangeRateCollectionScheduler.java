package com.travelpay.exchange.presentation.rate;

import com.travelpay.exchange.application.rate.ExchangeRateCollector;
import com.travelpay.exchange.application.rate.RateCollectionPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 모드별 정책이 정한 주기로 환율 수집을 실행합니다.
 * exchange.rate.collection.enabled=false이면 수집하지 않습니다. (테스트·수동 확인용)
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "exchange.rate.collection", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExchangeRateCollectionScheduler implements SchedulingConfigurer {

    private final ExchangeRateCollector collector;
    private final RateCollectionPolicy policy;

    public ExchangeRateCollectionScheduler(ExchangeRateCollector collector, RateCollectionPolicy policy) {
        this.collector = collector;
        this.policy = policy;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(collector::collectIfNeeded, policy.trigger());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void collectOnStartup() {
        collector.collectLatestIfEmpty();
    }
}
