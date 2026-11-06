package com.travelpay.exchange.infrastructure.cache.rate;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisConnectionWarmUpTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    /** 캐시 Redis가 꺼져 있어도 앱 시작을 막지 않습니다. 두 번 시도하고 넘어갑니다. */
    @Test
    void doesNotBlockStartupWhenRedisIsDown() {
        when(redis.execute(any(RedisCallback.class), anyBoolean()))
                .thenThrow(new RedisConnectionFailureException("refused"));

        assertDoesNotThrow(() -> new RedisConnectionWarmUp(redis).connect());
        verify(redis, times(2)).execute(any(RedisCallback.class), anyBoolean());
    }

    /** 첫 시도가 느려 실패해도 두 번째에 연결되면 거기서 멈춥니다. */
    @Test
    void stopsAfterFirstSuccessfulPing() {
        when(redis.execute(any(RedisCallback.class), anyBoolean()))
                .thenThrow(new RedisConnectionFailureException("init timed out"))
                .thenReturn("PONG");

        new RedisConnectionWarmUp(redis).connect();

        verify(redis, times(2)).execute(any(RedisCallback.class), anyBoolean());
    }
}
