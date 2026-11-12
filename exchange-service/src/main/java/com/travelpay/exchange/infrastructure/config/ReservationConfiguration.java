package com.travelpay.exchange.infrastructure.config;

import com.travelpay.exchange.domain.reservation.ReservationPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 예약 환전 설정값으로 도메인 정책(ReservationPolicy)을 만듭니다. 견적 정책(QuoteConfiguration)과 같은 방식입니다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReservationProperties.class)
public class ReservationConfiguration {

    @Bean
    public ReservationPolicy reservationPolicy(ReservationProperties properties) {
        return new ReservationPolicy(properties.maxPeriod(), properties.maxWaitingPerUser());
    }
}
