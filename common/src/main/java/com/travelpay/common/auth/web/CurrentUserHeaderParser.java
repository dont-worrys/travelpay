package com.travelpay.common.auth.web;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 내부 요청의 사용자 식별 헤더 형식을 검사합니다.
 * 호출 서비스의 신뢰를 확인한 경로에서 사용해야 하며, 형식 검사만으로 인증을 보장하지 않습니다.
 */
public final class CurrentUserHeaderParser {

    private CurrentUserHeaderParser() {
    }

    /** 두 헤더가 모두 없으면 empty를 반환하고, 불완전하거나 잘못된 값은 미인증 오류로 거절합니다. */
    public static Optional<AuthenticatedUser> parse(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");

        String userId = readSingleHeader(request, AuthHeaders.USER_ID);
        String role = readSingleHeader(request, AuthHeaders.USER_ROLE);
        if (userId == null && role == null) {
            return Optional.empty();
        }
        if (userId == null || role == null) {
            throw unauthorized();
        }

        try {
            UUID parsedId = UUID.fromString(userId);
            // UUID.fromString이 허용하는 축약형까지 받아들이지 않도록 표준 표기와 비교합니다.
            if (!parsedId.toString().equalsIgnoreCase(userId)) {
                throw unauthorized();
            }
            AuthRole parsedRole = AuthRole.valueOf(role);
            return Optional.of(new AuthenticatedUser(parsedId, parsedRole));
        } catch (IllegalArgumentException ignored) {
            throw unauthorized();
        }
    }

    private static String readSingleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (!values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        if (values.hasMoreElements()) {
            throw unauthorized();
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw unauthorized();
        }
        return normalized;
    }

    private static BusinessException unauthorized() {
        return new BusinessException(CommonErrorCode.UNAUTHORIZED);
    }
}
