package com.travelpay.exchange.presentation.rate;

import com.travelpay.exchange.application.rate.ExchangeRateCollector;
import com.travelpay.exchange.application.rate.RateCollectionPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "exchange.rate.collection", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ExchangeRateCollectionScheduler implements SchedulingConfigurer {

    private final ExchangeRateCollector collector;
    private final RateCollectionPolicy policy;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(collector::collectIfNeeded, policy.trigger());
    }

    /**
     * 서버가 뜬 직후 저장된 환율이 없으면 최근 고시분을 채웁니다.
     * ApplicationReadyEvent 리스너의 예외는 서버를 종료시키므로, 실패해도 로그만 남기고 다음 수집 주기에 맡깁니다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void collectOnStartup() {
        try {
            collector.collectLatestIfEmpty();
        } catch (RuntimeException e) {
            log.warn("시작 시 환율 수집에 실패했습니다. 다음 수집 주기에 다시 시도합니다.", e);
        }
    }
}
