package com.travelpay.gateway.request;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClientIpProperties.class)
class ClientIpConfiguration {

    @Bean
    ClientIpResolver clientIpResolver(ClientIpProperties properties) {
        return new ClientIpResolver(properties);
    }
}
