package com.travelpay.gateway.auth;

import java.net.URI;
import java.util.List;
import java.util.Set;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/** user-service의 세션 확인 API를 부릅니다. 결과는 회원 또는 거절 응답이고, 판단할 수 없는 실패는 503입니다. */
class SessionVerificationClient {

    private static final ParameterizedTypeReference<ApiResponse<AuthenticatedUser>> VERIFIED_BODY =
            new ParameterizedTypeReference<>() {
            };
    // 브라우저에 그대로 돌려줄 거절: 세션 없음·만료(401), 회원 상태·CSRF(403), 다른 탭 로그아웃과 겹침(409)
    private static final Set<Integer> REJECTIONS = Set.of(401, 403, 409);
    // 요청에서 그대로 복사해 보내는 헤더. 이 밖의 요청 헤더(Authorization 등)는 복사하지 않음
    private static final List<String> FORWARDED_HEADERS =
            List.of(HttpHeaders.COOKIE, AuthHeaders.CSRF_TOKEN, AuthHeaders.REQUEST_ID);

    private final WebClient webClient;
    private final URI uri;

    SessionVerificationClient(WebClient webClient, URI uri) {
        // 이 클라이언트에서만 숫자 역할과 success 값의 자동 형 변환을 거절합니다.
        var decoder = new JacksonJsonDecoder(JsonMapper.builder()
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS));
        this.webClient = webClient.mutate()
                .codecs(codecs -> codecs.defaultCodecs().jacksonJsonDecoder(decoder))
                .build();
        this.uri = uri;
    }

    Mono<SessionVerification> verify(ServerHttpRequest request) {
        return webClient.post().uri(uri)
                .headers(headers -> {
                    for (var name : FORWARDED_HEADERS) {
                        var values = request.getHeaders().getOrEmpty(name);
                        if (!values.isEmpty()) {
                            headers.addAll(name, values);
                        }
                    }
                    // 세션 확인 API는 항상 POST라서 브라우저 요청의 실제 메소드를 따로 알림
                    headers.set(AuthHeaders.ORIGINAL_METHOD, request.getMethod().name());
                })
                .exchangeToMono(SessionVerificationClient::read)
                // 시간 초과·연결 실패·형식 오류처럼 결과를 판단할 수 없는 실패는 모두 503
                .onErrorMap(error -> !(error instanceof BusinessException), error -> unavailable(error));
    }

    private static Mono<SessionVerification> read(ClientResponse response) {
        var status = response.statusCode();
        if (status.is2xxSuccessful()) {
            // 성공인데 회원 정보가 없으면 판단할 수 없으므로 503
            return response.bodyToMono(VERIFIED_BODY)
                    .flatMap(body -> Mono.justOrEmpty(body.data()))
                    .<SessionVerification>map(SessionVerification.Verified::new)
                    .switchIfEmpty(Mono.error(() -> unavailable(null)));
        }
        if (REJECTIONS.contains(status.value())) {
            return response.bodyToMono(byte[].class)
                    .defaultIfEmpty(new byte[0])
                    .map(body -> new SessionVerification.Rejected(status, body));
        }
        // 그 밖의 상태(5xx, 다른 4xx)는 인증 시스템 장애
        return Mono.error(() -> unavailable(null));
    }

    private static BusinessException unavailable(Throwable cause) {
        return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, cause);
    }
}
