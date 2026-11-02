package com.travelpay.gateway.request;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 경로에 . 이나 .. 조각, 인코딩된 / 나 \, ;(경로 매개변수)가 있으면 400. Gateway와 서비스가 경로를 다르게 해석하지 않게 합니다. */
@Component
class NormalizedPathFilter implements GlobalFilter, Ordered {

    // 다른 모든 Gateway 필터보다 먼저
    static final int ORDER = -300;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!isNormalized(exchange.getRequest().getPath().pathWithinApplication())) {
            return Mono.error(new BusinessException(CommonErrorCode.INVALID_REQUEST));
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    // 경로를 / 사이 조각으로 나눠 하나씩 검사. %2e(.)·%2f(/)처럼 인코딩한 글자는 원래 글자로 되돌린 뒤 검사
    private static boolean isNormalized(PathContainer path) {
        for (var element : path.elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                var value = segment.valueToMatch();
                if (value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\")
                        || !segment.parameters().isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }
}
