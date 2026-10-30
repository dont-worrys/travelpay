package com.travelpay.user.common.security.access;

import java.io.IOException;

import com.travelpay.common.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** 인증 실패를 API 오류 응답으로 처리합니다. */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorResponseWriter errorResponseWriter;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        var errorCode = exception instanceof AuthenticationServiceException
                ? CommonErrorCode.SERVICE_UNAVAILABLE
                : CommonErrorCode.UNAUTHORIZED;
        errorResponseWriter.write(response, errorCode);
    }
}
