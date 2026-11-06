package com.travelpay.wallet.application.reconciliation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.annotation.DurationFormat;
import org.springframework.format.datetime.standard.DurationFormatterUtils;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

/** 충전 대사 주기 실행. 기간 계산·오래된 RUNNING 정리는 ReconciliationRunner가 한다 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "travelpay.wallet.charge.reconciliation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ReconciliationScheduler {

    private static final String FIXED_DELAY_KEY = "travelpay.wallet.charge.reconciliation.fixed-delay";
    private static final Duration MIN_FIXED_DELAY = Duration.ofSeconds(1);

    private final ReconciliationRunner runner;

    /** 설정이 잘못되면 IllegalStateException으로 기동을 멈춘다 (@Scheduled 등록 전) */
    public ReconciliationScheduler(ReconciliationRunner runner,
                                   @Value("${" + FIXED_DELAY_KEY + ":10m}") String fixedDelay) {
        Duration delay;
        try {
            delay = DurationFormatterUtils.detectAndParse(fixedDelay, DurationFormat.Unit.MILLIS);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(FIXED_DELAY_KEY + " 형식이 잘못되었습니다: " + fixedDelay, e);
        }
        if (delay.compareTo(MIN_FIXED_DELAY) < 0) {
            throw new IllegalStateException(FIXED_DELAY_KEY + "는 1초 이상이어야 합니다: " + fixedDelay);
        }
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "${travelpay.wallet.charge.reconciliation.fixed-delay:10m}")
    public void reconcile() {
        try {
            runner.runScheduled();
        } catch (RuntimeException e) {
            log.error("충전 대사 스케줄 실행 실패 exception={}", e.getClass().getName());
        }
    }
}
