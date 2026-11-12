package com.travelpay.gateway.request;

import java.util.List;
import java.util.UUID;

import com.travelpay.common.auth.AuthHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 클라이언트의 내부용·추적 헤더를 지우고, Request-Id와 Client-Ip를 Gateway가 새로 붙입니다. */
@Component
public class InternalHeaderResetFilter implements GlobalFilter, Ordered {

    // 경로 검사 후, 세션 인증 전에 실행
    public static final int ORDER = NormalizedPathFilter.ORDER + 100;

    // Cloudflare가 넣는 실제 클라이언트 IP 헤더
    static final String CF_CONNECTING_IP = "CF-Connecting-IP";

    // Cloudflare가 ALB 원본 인증용으로 붙이는 비밀 헤더. ALB 규칙이 확인한 뒤에는 쓸 곳이 없으므로 업무 서비스로 넘기지 않음
    static final String ORIGIN_AUTH = "Origin-Auth";

    // 업무 서비스로 넘기지 않고 지우는 헤더: 클라이언트가 보내도 믿지 않는 내부·추적 헤더와 원본 인증 헤더
    private static final List<String> UNTRUSTED_HEADERS = List.of(AuthHeaders.USER_ID, AuthHeaders.USER_ROLE,
            AuthHeaders.ORIGINAL_METHOD, AuthHeaders.REQUEST_ID, AuthHeaders.CLIENT_IP, CF_CONNECTING_IP, ORIGIN_AUTH,
            "traceparent", "tracestate", "baggage", "b3", "X-B3-TraceId", "X-B3-SpanId", "X-B3-ParentSpanId",
            "X-B3-Sampled", "X-B3-Flags", "X-Amzn-Trace-Id");

    private final ClientIpResolver clientIpResolver;

    InternalHeaderResetFilter(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var original = exchange.getRequest();
        // 지우기 전에 CF-Connecting-IP 값을 모두 읽음. 믿을지는 ClientIpResolver가 판단
        var clientIp = clientIpResolver.resolve(original.getRemoteAddress(),
                original.getHeaders().getOrEmpty(CF_CONNECTING_IP));
        var request = original.mutate()
                .headers(headers -> {
                    UNTRUSTED_HEADERS.forEach(headers::remove);
                    removeInternalNamesFromConnection(headers);
                    headers.set(AuthHeaders.REQUEST_ID, UUID.randomUUID().toString());
                    if (clientIp != null) {
                        headers.set(AuthHeaders.CLIENT_IP, clientIp);
                    }
                })
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    private static void removeInternalNamesFromConnection(HttpHeaders headers) {
        // 뒤에서 실행되는 SCG가 Gateway의 내부 헤더를 지우지 않도록 그 이름만 목록에서 뺍니다.
        var options = headers.getConnection().stream()
                .filter(name -> UNTRUSTED_HEADERS.stream().noneMatch(name::equalsIgnoreCase))
                .toList();
        if (options.isEmpty()) {
            headers.remove(HttpHeaders.CONNECTION);
        } else {
            headers.setConnection(options);
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
