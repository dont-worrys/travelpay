package com.travelpay.exchange.infrastructure.cache.rate;

import com.travelpay.exchange.infrastructure.persistence.quote.QuoteRedisConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 앱이 뜨면 환전 Redis(redis-cache)에 미리 연결합니다(PING). 연결은 두 개입니다.
 * - 환율 캐시용 기본 연결 (명령 대기 200ms)
 * - 견적 저장소 전용 연결 (명령 대기 exchange.quote.redis-timeout, #261)
 *
 * Lettuce는 첫 명령 때 연결하며, 연결 준비(인증 등)에도 명령 대기 한도를 적용합니다.
 * 첫 연결은 클래스 로딩 등으로 느려 한도를 넘길 수 있어, 그대로 두면 첫 사용자 요청이 캐시를 못 쓰거나(DB로 감) 견적 발급이 503이 됩니다.
 * 견적은 환율 캐시를 꺼도(exchange.rate.cache.enabled=false) Redis에 저장하므로, 캐시 설정과 관계없이 항상 연결합니다.
 * 실패해도 앱 시작을 막지 않습니다. 다음 명령 때 다시 연결합니다. 한 연결이 실패해도 다른 연결은 시도합니다.
 */
@Slf4j
@Component
public class RedisConnectionWarmUp {

    /** 첫 시도가 클래스 로딩으로 느려 실패해도 두 번째는 대부분 바로 연결됩니다. */
    private static final int ATTEMPTS = 2;

    private final StringRedisTemplate cacheRedis;
    private final StringRedisTemplate quoteRedis;

    @Autowired
    public RedisConnectionWarmUp(StringRedisTemplate cacheRedis, QuoteRedisConnection quoteConnection) {
        this(cacheRedis, quoteConnection.template());
    }

    /** 테스트용: 두 연결을 직접 받습니다. */
    RedisConnectionWarmUp(StringRedisTemplate cacheRedis, StringRedisTemplate quoteRedis) {
        this.cacheRedis = cacheRedis;
        this.quoteRedis = quoteRedis;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void connect() {
        ping("환율 캐시", cacheRedis, "환율은 DB에서 읽고");
        ping("견적 저장소", quoteRedis, "견적은 503이며");
    }

    private static void ping(String name, StringRedisTemplate redis, String whileDown) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                redis.execute(connection -> connection.ping(), true);
                log.info("환전 Redis 연결 확인: {} (시도 {}번)", name, attempt);
                return;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        log.warn("환전 Redis({})에 연결하지 못했습니다. {} 다음 명령 때 다시 연결합니다: {}", name, whileDown, last.toString());
    }
}
