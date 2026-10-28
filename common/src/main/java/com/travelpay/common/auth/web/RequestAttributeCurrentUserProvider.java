package com.travelpay.common.auth.web;

import java.util.Optional;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.CurrentUserProvider;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 현재 스레드의 Servlet 요청 속성에서 요청자 정보를 읽습니다.
 * 요청 진입부는 신뢰 검증 후 CURRENT_USER_ATTRIBUTE에 AuthenticatedUser를 보관해야 합니다.
 */
public class RequestAttributeCurrentUserProvider implements CurrentUserProvider {

    /** 저장하는 코드와 조회하는 코드가 함께 사용하는 요청 속성 이름입니다. */
    public static final String CURRENT_USER_ATTRIBUTE = AuthenticatedUser.class.getName();

    @Override
    public Optional<AuthenticatedUser> findCurrentUser() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }

        Object stored = attributes.getAttribute(CURRENT_USER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (stored == null) {
            return Optional.empty();
        }
        if (!(stored instanceof AuthenticatedUser user)) {
            throw new IllegalStateException("Current user request attribute must be AuthenticatedUser");
        }
        return Optional.of(user);
    }
}
