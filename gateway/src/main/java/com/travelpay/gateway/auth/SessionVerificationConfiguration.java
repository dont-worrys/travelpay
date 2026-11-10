package com.travelpay.gateway.auth;

import io.micrometer.observation.ObservationRegistry;
import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/** 세션 확인 API 전용 WebClient로 SessionVerificationClient를 만듭니다. WebClient는 빈으로 내놓지 않아 다른 호출과 섞이지 않습니다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionVerificationProperties.class)
class SessionVerificationConfiguration {

    @Bean
    SessionVerificationClient sessionVerificationClient(ReactorLoadBalancerExchangeFilterFunction loadBalancer,
            ObservationRegistry observationRegistry, SessionVerificationProperties properties) {
        // 연결·응답 제한 시간. 넘으면 SessionVerificationClient가 503으로 바꿈
        var httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.connectTimeout().toMillis()))
                .responseTimeout(properties.responseTimeout());
        var webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                // 주소의 user-service를 Eureka에서 찾은 실제 주소로 바꿈
                .filter(loadBalancer)
                // 이 호출도 요청의 traceId를 이어 받게 함(traceparent 헤더를 붙임)
                .observationRegistry(observationRegistry)
                .build();
        return new SessionVerificationClient(webClient, properties.uri());
    }
}
