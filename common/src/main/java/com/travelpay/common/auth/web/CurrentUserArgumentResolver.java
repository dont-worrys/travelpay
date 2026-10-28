package com.travelpay.common.auth.web;

import java.util.Objects;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.CurrentUserProvider;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 컨트롤러의 @CurrentUser AuthenticatedUser 파라미터에 Provider가 제공하는 요청자 정보를 전달합니다.
 * @CurrentUser는 @RequestBody, @RequestParam 등 다른 입력 바인딩 애너테이션과 함께 사용하지 않습니다.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final CurrentUserProvider currentUserProvider;

    public CurrentUserArgumentResolver(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = Objects.requireNonNull(currentUserProvider, "currentUserProvider");
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        // @CurrentUser를 선택하고, 지원하는 타입인지는 resolveArgument에서 검사합니다.
        return parameter.hasParameterAnnotation(CurrentUser.class);
    }

    @Override
    public AuthenticatedUser resolveArgument(
            MethodParameter parameter,
            @Nullable ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            @Nullable WebDataBinderFactory binderFactory
    ) {
        if (!AuthenticatedUser.class.equals(parameter.getParameterType())) {
            throw new IllegalStateException("@CurrentUser parameter must be AuthenticatedUser");
        }
        return currentUserProvider.requireCurrentUser();
    }
}
