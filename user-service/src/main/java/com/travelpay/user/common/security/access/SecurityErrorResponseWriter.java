package com.travelpay.user.common.security.access;

import java.io.IOException;

import com.travelpay.common.error.ErrorCode;
import com.travelpay.common.error.ErrorResponse;
import com.travelpay.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;

/** 보안 처리기가 선택한 오류를 공통 JSON 형식으로 응답합니다. */
@Component
@RequiredArgsConstructor
public class SecurityErrorResponseWriter {

    private final JsonMapper mapper;

    public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        byte[] body = mapper.writeValueAsBytes(ApiResponse.failure(ErrorResponse.of(errorCode)));

        // 기존 쿠키와 보안 헤더는 유지하고, 아직 전송하지 않은 본문만 비웁니다.
        response.resetBuffer();
        response.setStatus(errorCode.getStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
