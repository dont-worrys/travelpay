package com.travelpay.gateway.auth;

import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** 로그인 없이 쓰는 API 목록. 여기 없는 요청은 모두 세션 확인 API를 거칩니다(새 경로도 목록에 넣기 전까지는 보호). */
final class PublicEndpoints {

    private static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint(HttpMethod.GET, "/api/v1/auth/csrf"),
            new Endpoint(HttpMethod.GET, "/api/v1/terms"),
            new Endpoint(HttpMethod.POST, "/api/v1/users"),
            new Endpoint(HttpMethod.POST, "/api/v1/auth/login"),
            // 정지·종료 회원도 로그아웃할 수 있게 세션 확인 API(ACTIVE만 통과)를 거치지 않음. user-service가 직접 확인
            new Endpoint(HttpMethod.POST, "/api/v1/auth/logout"),
            new Endpoint(HttpMethod.GET, "/api/v1/exchange-rates/**"));

    private PublicEndpoints() {
    }

    static boolean contains(ServerHttpRequest request) {
        return ENDPOINTS.stream().anyMatch(endpoint -> endpoint.matches(request));
    }

    private record Endpoint(HttpMethod method, PathPattern path) {

        Endpoint(HttpMethod method, String path) {
            this(method, PathPatternParser.defaultInstance.parse(path));
        }

        boolean matches(ServerHttpRequest request) {
            return method.equals(request.getMethod()) && path.matches(request.getPath().pathWithinApplication());
        }
    }
}
