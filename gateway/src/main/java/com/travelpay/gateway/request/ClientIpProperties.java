package com.travelpay.gateway.request;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** CF-Connecting-IP를 믿을 연결 상대 대역. Cloudflare 공개 대역과, Cloudflare 요청만 넘기는 우리 쪽 프록시(ALB·cloudflared) 대역입니다. */
@ConfigurationProperties("travelpay.gateway.client-ip")
public record ClientIpProperties(List<String> cloudflareAddresses, List<String> proxyAddresses) {

    public ClientIpProperties {
        cloudflareAddresses = cloudflareAddresses == null ? List.of() : List.copyOf(cloudflareAddresses);
        proxyAddresses = proxyAddresses == null ? List.of() : List.copyOf(proxyAddresses);
    }
}
