package com.travelpay.user.common.security.access;

import java.io.IOException;

import com.travelpay.common.error.CommonErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** 세션 확인 API의 Original-Method 헤더가 없거나, 두 개 이상이거나, 허용 목록에 없으면 400. CSRF 필터보다 먼저 실행합니다. */
public class OriginalMethodHeaderFilter extends OncePerRequestFilter {

    private final RequestMatcher sessionVerificationRequests;
    private final SecurityErrorResponseWriter errorResponseWriter;

    public OriginalMethodHeaderFilter(RequestMatcher sessionVerificationRequests,
            SecurityErrorResponseWriter errorResponseWriter) {
        this.sessionVerificationRequests = sessionVerificationRequests;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !sessionVerificationRequests.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (OriginalMethod.from(request).isEmpty()) {
            errorResponseWriter.write(response, CommonErrorCode.INVALID_REQUEST);
            return;
        }
        chain.doFilter(request, response);
    }
}
