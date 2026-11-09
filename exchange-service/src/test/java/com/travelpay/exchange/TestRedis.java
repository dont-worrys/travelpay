package com.travelpay.exchange;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

/**
 * 견적 저장소(Redis)가 필요한 통합 테스트가 함께 쓰는 Redis 컨테이너입니다(#223).
 * 견적은 Redis에만 저장되므로, 견적을 발급하거나 신청하는 테스트는 Redis가 있어야 합니다.
 *
 * 처음 쓰일 때 한 번 띄우고 테스트가 끝날 때까지 재사용합니다(테스트 클래스마다 띄우지 않음).
 * 견적 키는 무작위 ID라 테스트끼리 겹치지 않습니다. 견적 개수를 세는 테스트는 직접 비우고 시작합니다.
 * 환율 캐시까지 이 Redis를 쓰면 테스트마다 다시 넣는 환율과 캐시가 어긋나므로, 이 Redis를 쓰는 테스트는 환율 캐시를 끕니다
 * (exchange.rate.cache.enabled=false). 환율 캐시 자체를 검증하는 테스트는 따로 Redis를 띄웁니다.
 */
public final class TestRedis {

    private static final String PASSWORD = "test-only";

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2.11-bookworm")
            .withCommand("redis-server", "--requirepass", PASSWORD, "--maxmemory-policy", "noeviction")
            .withExposedPorts(6379);

    private TestRedis() {
    }

    /** 컨테이너를 (처음이면) 띄우고 앱이 이 Redis에 붙도록 설정합니다. */
    public static void register(DynamicPropertyRegistry registry) {
        if (!REDIS.isRunning()) {
            REDIS.start();
        }
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> PASSWORD);
        registry.add("exchange.rate.cache.enabled", () -> "false");
    }
}
