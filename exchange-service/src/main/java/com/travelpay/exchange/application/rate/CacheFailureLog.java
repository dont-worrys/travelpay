package com.travelpay.exchange.application.rate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 캐시 실패 경고를 1분에 한 번만 남깁니다.
 * 캐시 Redis가 꺼지면 조회 요청마다 실패하므로, 매번 경고를 남기면 같은 줄이 초당 수백 번 쌓여 다른 로그를 가립니다.
 * 생략한 건수는 다음 경고에 함께 남기고, 실패 횟수 자체는 캐시 지표(result=error)로 셉니다.
 */
@Slf4j
@Component
public class CacheFailureLog {

    static final Duration INTERVAL = Duration.ofMinutes(1);

    private final Clock clock;
    private final AtomicReference<Instant> lastWarnedAt = new AtomicReference<>();
    private final AtomicLong suppressed = new AtomicLong();

    public CacheFailureLog(Clock clock) {
        this.clock = clock;
    }

    /** @param action 실패한 캐시 작업 (예: "조회", "갱신") */
    public void warn(String action, RuntimeException failure) {
        Instant now = clock.instant();
        Instant last = lastWarnedAt.get();
        boolean recentlyWarned = last != null && now.isBefore(last.plus(INTERVAL));
        // 동시에 여러 요청이 실패해도 compareAndSet에 이긴 한 요청만 경고합니다.
        if (recentlyWarned || !lastWarnedAt.compareAndSet(last, now)) {
            suppressed.incrementAndGet();
            log.debug("환율 캐시 {} 실패 (경고 생략): {}", action, failure.toString());
            return;
        }
        log.warn("환율 캐시 {} 실패, DB로 처리합니다. 직전 경고 이후 생략한 실패 {}건: {}",
                action, suppressed.getAndSet(0), failure.toString());
    }
}
