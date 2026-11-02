package com.travelpay.common.auth;

import java.util.List;

public final class AuthHeaders {

    public static final String USER_ID = "User-Id";
    public static final String USER_ROLE = "User-Role";
    public static final String REQUEST_ID = "Request-Id";

    // Gateway가 세션 확인 API에 브라우저 요청의 실제 메소드를 담는 헤더
    public static final String ORIGINAL_METHOD = "Original-Method";

    // 세션 확인 API가 받는 메소드(명세). 그 밖의 메소드는 Gateway가 405로 거절
    public static final List<String> ALLOWED_ORIGINAL_METHODS =
            List.of("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE");

    // CSRF 토큰 헤더. Gateway는 세션 확인 API로 그대로 넘김
    public static final String CSRF_TOKEN = "Csrf-Token";

    // Gateway가 확인한 실제 클라이언트 IP. user-service의 Tomcat이 이 값을 클라이언트 주소로 씀
    public static final String CLIENT_IP = "Client-Ip";

    private AuthHeaders() {
    }
}
