package com.travelpay.exchange.infrastructure.persistence.quote;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 견적 저장소 전용 Redis 연결입니다(#261). 환율 캐시와 같은 Redis(redis-cache)에 붙지만 연결은 따로 둡니다.
 *
 * Lettuce의 명령 대기 한도는 연결마다 하나라서, 환율 캐시(200ms)와 다른 한도를 쓰려면 연결을 나눠야 합니다.
 * 캐시는 200ms를 넘으면 DB로 읽으면 되지만, 견적은 Redis가 원본이라 넘으면 바로 503입니다.
 * 그래서 견적만 더 긴 한도(exchange.quote.redis-timeout)로 순간 지연을 기다립니다.
 *
 * StringRedisTemplate이나 RedisConnectionFactory 타입의 빈으로 등록하지 않고 이 클래스로 감쌉니다.
 * 그 타입의 빈이 하나라도 있으면 Spring Boot가 기본 Redis 연결(환율 캐시용)을 만들지 않기 때문입니다.
 * 연결 수명은 이 빈이 관리합니다(앱 종료 때 destroy).
 */
public final class QuoteRedisConnection {

    private final LettuceConnectionFactory connectionFactory;
    private final StringRedisTemplate template;

    /** 이미 시작한(start) 연결 팩토리를 받습니다. */
    public QuoteRedisConnection(LettuceConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
        this.template = new StringRedisTemplate(connectionFactory);
    }

    public StringRedisTemplate template() {
        return template;
    }

    /** 명령 대기 한도 확인용(설정 테스트)입니다. */
    public LettuceConnectionFactory connectionFactory() {
        return connectionFactory;
    }

    public void destroy() {
        connectionFactory.destroy();
    }
}
