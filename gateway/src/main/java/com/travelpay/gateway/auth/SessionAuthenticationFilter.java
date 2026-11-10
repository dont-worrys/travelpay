package com.travelpay.gateway.auth;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.gateway.request.InternalHeaderResetFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 공개 API가 아닌 요청은 세션 확인 API를 거칩니다. 통과하면 회원 헤더를 붙이고, 거절이면 user-service의 상태와 본문을 돌려줍니다. */
@Component
class SessionAuthenticationFilter implements GlobalFilter, Ordered {

    // 헤더 리셋 다음, 쿠키를 지우는 라우트 필터(순서 1부터)보다 먼저. 그래야 세션 확인에 원래 쿠키를 넘길 수 있음
    static final int ORDER = InternalHeaderResetFilter.ORDER + 100;

    private final SessionVerificationClient sessionVerificationClient;
    private final Duration verificationTimeout;

    SessionAuthenticationFilter(SessionVerificationClient sessionVerificationClient,
            SessionVerificationProperties properties) {
        this.sessionVerificationClient = sessionVerificationClient;
        // 전체 호출 상한은 연결·읽기 제한과 별도로 적용합니다.
        this.verificationTimeout = properties.totalTimeout();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (PublicEndpoints.contains(exchange.getRequest())) {
            return chain.filter(exchange);
        }
        if (!AuthHeaders.ALLOWED_ORIGINAL_METHODS.contains(exchange.getRequest().getMethod().name())) {
            // 세션 확인 API가 받지 않는 메소드는 호출 전에 405와 허용 메소드 목록으로 거절합니다.
            return Mono.error(new MethodNotAllowedException(exchange.getRequest().getMethod(),
                    AuthHeaders.ALLOWED_ORIGINAL_METHODS.stream().map(HttpMethod::valueOf).toList()));
        }
        return sessionVerificationClient.verify(exchange.getRequest())
                // 연결 전 대기부터 본문 해석 완료까지 제한하며, 이후 업무 서비스 호출은 이 범위 밖입니다.
                .timeout(verificationTimeout)
                .onErrorMap(TimeoutException.class,
                        error -> new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, error))
                .flatMap(result -> switch (result) {
                    case SessionVerification.Verified verified -> chain.filter(withMember(exchange, verified.user()));
                    case SessionVerification.Rejected rejected -> reject(exchange, rejected);
                });
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    // 세션 확인 API가 알려 준 회원으로 User-Id·User-Role을 붙임. 브라우저가 보낸 값은 앞 필터가 이미 지움
    private static ServerWebExchange withMember(ServerWebExchange exchange, AuthenticatedUser user) {
        var request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(AuthHeaders.USER_ID, user.userId().toString());
                    headers.set(AuthHeaders.USER_ROLE, user.role().name());
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    // user-service의 거절(401·403·409)을 상태와 본문 그대로 돌려줌. HEAD 요청이면 Netty가 본문을 빼고 보냄
    private static Mono<Void> reject(ServerWebExchange exchange, SessionVerification.Rejected rejected) {
        var response = exchange.getResponse();
        response.setStatusCode(rejected.status());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // 인증 거절 응답의 저장 금지를 Gateway에서도 명시합니다.
        response.getHeaders().setCacheControl(CacheControl.noStore());
        return response.writeWith(Mono.just(response.bufferFactory().wrap(rejected.body())));
    }
}
