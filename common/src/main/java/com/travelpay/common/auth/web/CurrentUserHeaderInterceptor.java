package com.travelpay.common.auth.web;

import com.travelpay.common.auth.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 컨트롤러 실행 전에 회원 헤더를 해석해 현재 요청 속성에 보관합니다.
 * 서비스 인증이나 세션 검증은 수행하지 않으며, MVP에서 전달받은 헤더의 형식만 검사합니다.
 * 자동 등록은 MVC 자동 설정의 travelpay.auth.header-context.enabled 옵션으로 활성화합니다.
 */
public class CurrentUserHeaderInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object stored = request.getAttribute(RequestAttributeCurrentUserProvider.CURRENT_USER_ATTRIBUTE);
        if (stored != null) {
            if (!(stored instanceof AuthenticatedUser)) {
                throw new IllegalStateException("Current user request attribute must be AuthenticatedUser");
            }
            // 서버의 앞선 처리에서 준비한 요청자 정보는 헤더로 덮어쓰지 않습니다.
            return true;
        }

        CurrentUserHeaderParser.parse(request).ifPresent(user ->
                request.setAttribute(RequestAttributeCurrentUserProvider.CURRENT_USER_ATTRIBUTE, user));
        return true;
    }
}
