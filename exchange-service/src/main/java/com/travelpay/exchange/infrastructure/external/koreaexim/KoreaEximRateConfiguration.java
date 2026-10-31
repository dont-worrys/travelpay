package com.travelpay.exchange.infrastructure.external.koreaexim;

import java.net.http.HttpClient;
import java.time.ZoneId;

import com.travelpay.exchange.application.rate.RateCollectionPolicy;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import com.travelpay.exchange.domain.rate.RateFreshnessPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * exchange.rate.mode=koreaexim이면 한국수출입은행 수집기와 그 모드의 정책을 함께 등록합니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "exchange.rate", name = "mode", havingValue = "koreaexim")
@EnableConfigurationProperties(KoreaEximProperties.class)
public class KoreaEximRateConfiguration {

    /** 수출입은행은 서울 시간 기준으로 고시합니다. */
    static final ZoneId PUBLISH_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public ExchangeRateProvider koreaEximRateProvider(KoreaEximProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
        return new KoreaEximRateProvider(restClient, properties.apiKey());
    }

    @Bean
    public RateCollectionPolicy koreaEximCollectionPolicy(KoreaEximProperties properties) {
        return new KoreaEximCollectionPolicy(properties.collectCron(), PUBLISH_ZONE);
    }

    @Bean
    public RateFreshnessPolicy koreaEximFreshnessPolicy(KoreaEximProperties properties) {
        return new KoreaEximFreshnessPolicy(properties.staleAfterDays(), PUBLISH_ZONE);
    }
}
