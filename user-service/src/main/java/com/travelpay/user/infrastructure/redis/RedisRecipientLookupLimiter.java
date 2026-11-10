package com.travelpay.user.infrastructure.redis;

import java.util.List;
import java.util.UUID;

import com.travelpay.user.application.service.RecipientLookupLimiter;
import com.travelpay.user.common.config.RecipientLookupProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 최근 집계 기간에 허용한 조회를 요청자별로 세션 Redis에 기록합니다.
 * 지난 기록 정리·개수 확인·새 기록 추가를 하나의 Lua 스크립트로 실행합니다.
 */
@Component
@EnableConfigurationProperties(RecipientLookupProperties.class)
public class RedisRecipientLookupLimiter implements RecipientLookupLimiter {

    private static final String PREFIX = "travelpay:user:recipient-lookup:";

    // KEYS[1]: 요청자별 조회 기록. ARGV: 허용 횟수, 집계 기간(ms), 호출별 식별자.
    // Redis 7+의 기본 스크립트 플래그로 메모리 한도 초과 상태에서는 실행을 거절합니다.
    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
            #!lua
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local period = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - period)
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[1]) then
                return 0
            end
            redis.call('ZADD', KEYS[1], now, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RecipientLookupProperties properties;

    public RedisRecipientLookupLimiter(StringRedisTemplate redis, RecipientLookupProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public boolean tryAcquire(UUID requesterId) {
        if (requesterId == null) {
            throw new IllegalArgumentException("요청자 회원 ID는 비어 있을 수 없습니다.");
        }
        var result = redis.execute(ACQUIRE, List.of(PREFIX + requesterId),
                String.valueOf(properties.limit()), String.valueOf(properties.period().toMillis()),
                UUID.randomUUID().toString());
        if (result == null || (result != 0L && result != 1L)) {
            throw new DataRetrievalFailureException("수취인 조회 횟수 제한 결과를 확인할 수 없습니다.");
        }
        return result == 1L;
    }
}
