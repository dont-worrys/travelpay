package com.travelpay.exchange.presentation.reservation;

import com.travelpay.exchange.application.reservation.ExchangeReservationExpirer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 만료 시각이 지난 대기 예약을 주기적으로 만료로 바꿉니다.
 * 서버가 여러 대면 모두 돌지만, 같은 행은 한 곳만 잠그고 바꾸므로(SKIP LOCKED) 겹쳐도 같은 결과입니다.
 * exchange.reservation.expiry.enabled=false이면 돌지 않습니다. (테스트·수동 확인용)
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "exchange.reservation.expiry", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ExchangeReservationExpiryScheduler {

    private final ExchangeReservationExpirer expirer;
    private final int batchSize;

    /** 배치 크기가 1보다 작으면 서버 시작 단계에서 실패합니다. */
    public ExchangeReservationExpiryScheduler(ExchangeReservationExpirer expirer,
                                              @Value("${exchange.reservation.expiry.batch-size:500}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalStateException("exchange.reservation.expiry.batch-size는 1 이상이어야 합니다: " + batchSize);
        }
        this.expirer = expirer;
        this.batchSize = batchSize;
    }

    /** 한 주기가 실패해도 다음 주기는 돌도록 예외를 로그로만 남깁니다. */
    @Scheduled(initialDelayString = "${exchange.reservation.expiry.fixed-delay:1m}",
            fixedDelayString = "${exchange.reservation.expiry.fixed-delay:1m}")
    public void expire() {
        try {
            expirer.expire(batchSize);
        } catch (RuntimeException e) {
            log.error("예약 만료 주기 실패: exception={}", e.getClass().getName(), e);
        }
    }
}
