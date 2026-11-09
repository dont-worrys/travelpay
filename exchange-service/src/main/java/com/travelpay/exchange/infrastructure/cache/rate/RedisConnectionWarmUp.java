package com.travelpay.exchange.infrastructure.cache.rate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 앱이 뜨면 환전 Redis(redis-cache: 환율 캐시 + 견적 저장소)에 미리 한 번 연결합니다(PING).
 * Lettuce는 첫 명령 때 연결하며, 연결 준비(인증 등)에도 명령 타임아웃(200ms)을 적용합니다.
 * 첫 연결은 클래스 로딩 등으로 느려 200ms를 넘길 수 있어, 그대로 두면 첫 사용자 요청이 캐시를 못 쓰거나(DB로 감) 견적 발급이 503이 됩니다.
 * 견적은 환율 캐시를 꺼도(exchange.rate.cache.enabled=false) Redis에 저장하므로, 캐시 설정과 관계없이 항상 연결합니다.
 * 실패해도 앱 시작을 막지 않습니다. 다음 명령 때 다시 연결합니다.
 */
@Slf4j
@Component
public class RedisConnectionWarmUp {

    /** 첫 시도가 클래스 로딩으로 느려 실패해도 두 번째는 대부분 바로 연결됩니다. */
    private static final int ATTEMPTS = 2;

    private final StringRedisTemplate redis;

    public RedisConnectionWarmUp(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void connect() {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                redis.execute(connection -> connection.ping(), true);
                log.info("환전 Redis 연결 확인 (시도 {}번)", attempt);
                return;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        log.warn("환전 Redis에 연결하지 못했습니다. 환율은 DB에서 읽고 견적은 503이며, 다음 명령 때 다시 연결합니다: {}", last.toString());
    }
}
