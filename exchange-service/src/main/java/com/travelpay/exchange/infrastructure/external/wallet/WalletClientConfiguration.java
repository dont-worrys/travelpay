package com.travelpay.exchange.infrastructure.external.wallet;

import java.net.http.HttpClient;

import com.travelpay.exchange.domain.wallet.WalletBalanceReader;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 지갑 서비스 클라이언트를 등록합니다.
 * 주소의 호스트(wallet-service)는 Eureka에서 실제 인스턴스로 바꿉니다. (@LoadBalanced)
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WalletClientProperties.class)
public class WalletClientConfiguration {

    @Bean
    @LoadBalanced
    public RestClient.Builder walletRestClientBuilder(WalletClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.balancesUrl().toString())
                .requestFactory(requestFactory);
    }

    @Bean
    public WalletBalanceReader walletBalanceReader(RestClient.Builder walletRestClientBuilder) {
        return new WalletBalanceClient(walletRestClientBuilder.build());
    }
}
