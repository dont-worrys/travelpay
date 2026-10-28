package com.travelpay.exchange.infrastructure.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 환전 서비스의 날짜 판단(오늘 고시일 등)은 서울 시간을 기준으로 합니다. */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(SERVICE_ZONE);
    }
}
