package com.travelpay.user.common.security.session;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

/** 기존 세션 저장 형식을 유지하고, 읽을 수 없는 값만 별도의 표시로 구분합니다. */
public final class RedisSessionSerializer implements RedisSerializer<Object> {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionSerializer.class);

    private final JdkSerializationRedisSerializer delegate = new JdkSerializationRedisSerializer();

    @Override
    public byte[] serialize(@Nullable Object value) {
        if (isUnreadable(value)) {
            throw new SerializationException("해석할 수 없는 세션 값은 저장할 수 없습니다.");
        }
        return delegate.serialize(value);
    }

    @Override
    public @Nullable Object deserialize(byte @Nullable [] bytes) {
        try {
            return delegate.deserialize(bytes);
        }
        catch (SerializationException exception) {
            // 호환되지 않는 배포로 로그인이 한꺼번에 풀리는 것을 알 수 있게 남깁니다.
            // 예외 메시지는 저장된 값을 담을 수 있어 원인 종류만 기록합니다.
            log.warn("세션 값을 읽지 못했습니다: {}",
                    NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName());
            return UnreadableValue.INSTANCE;
        }
    }

    public static boolean isUnreadable(@Nullable Object value) {
        return value == UnreadableValue.INSTANCE;
    }

    private enum UnreadableValue {
        INSTANCE
    }
}
