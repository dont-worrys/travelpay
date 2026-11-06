package com.travelpay.exchange.infrastructure.config;

import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisClientConfigurationTest {

    /** Lettuce 기본값은 끊긴 동안 명령을 쌓아 두는 것(DEFAULT)이라, 바로 거절하도록 바꿨는지 확인합니다. */
    @Test
    void rejectsCommandsWhileDisconnected() {
        ClientOptions.Builder builder = ClientOptions.builder();

        new RedisClientConfiguration().rejectCommandsWhileDisconnected().customize(builder);

        assertEquals(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS, builder.build().getDisconnectedBehavior());
    }
}
