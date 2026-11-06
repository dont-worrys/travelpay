package com.travelpay.exchange.infrastructure.config;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 캐시 Redis 클라이언트(Lettuce) 옵션입니다. 연결 주소·타임아웃은 application.yml의 spring.data.redis를 씁니다.
 *
 * Lettuce는 기본으로, 연결이 끊긴 동안 보낸 명령을 버리지 않고 쌓아 두었다가 다시 연결되면 보냅니다.
 * 그 사이 호출한 쪽은 명령 타임아웃(200ms)까지 기다려야 하고, 쌓인 명령은 메모리를 차지합니다.
 * 캐시는 실패하면 DB로 조회하면 되므로, 끊긴 동안에는 명령을 바로 거절(REJECT_COMMANDS)해 기다리지 않고 DB로 가게 합니다.
 * 재연결은 Lettuce가 뒤에서 계속 시도하므로 Redis가 돌아오면 다시 캐시를 씁니다.
 */
@Configuration(proxyBeanMethods = false)
public class RedisClientConfiguration {

    @Bean
    LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
        return options -> options.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
