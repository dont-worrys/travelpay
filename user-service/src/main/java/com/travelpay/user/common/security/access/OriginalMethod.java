package com.travelpay.user.common.security.access;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import com.travelpay.common.auth.AuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;

/** 세션 확인 API에 온 Original-Method 값(브라우저 요청의 실제 메소드). 이 API는 늘 POST라 CSRF 확인 여부를 이 값으로 정합니다. */
public record OriginalMethod(HttpMethod method) {

    // 상태를 바꾸는 메소드(명세). 이때만 CSRF 토큰을 확인
    private static final Set<HttpMethod> STATE_CHANGING_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    // 헤더가 정확히 1개이고 허용 목록(대문자)에 있을 때만 값이 있음
    public static Optional<OriginalMethod> from(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders(AuthHeaders.ORIGINAL_METHOD));
        if (values.size() != 1 || !AuthHeaders.ALLOWED_ORIGINAL_METHODS.contains(values.getFirst())) {
            return Optional.empty();
        }
        return Optional.of(new OriginalMethod(HttpMethod.valueOf(values.getFirst())));
    }

    // 상태를 바꾸는 메소드면 CSRF 토큰도 확인
    public boolean changesState() {
        return STATE_CHANGING_METHODS.contains(method);
    }
}
