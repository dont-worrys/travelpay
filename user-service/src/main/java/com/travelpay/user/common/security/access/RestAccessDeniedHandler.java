package com.travelpay.user.common.security.access;

import java.io.IOException;

import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

/** CSRF 실패와 권한 거절을 구분해 API 오류 응답으로 처리합니다. */
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponseWriter errorResponseWriter;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        ErrorCode errorCode;
        if (exception instanceof CsrfException) {
            errorCode = CommonErrorCode.CSRF_INVALID;
        }
        else if (exception instanceof MemberAccessDeniedException memberException) {
            errorCode = memberException.getErrorCode();
        }
        else {
            errorCode = CommonErrorCode.FORBIDDEN;
        }
        errorResponseWriter.write(response, errorCode);
    }
}
