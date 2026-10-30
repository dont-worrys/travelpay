package com.travelpay.user.common.security.access;

import java.io.IOException;
import java.util.stream.Stream;

import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorCode;
import com.travelpay.user.common.error.UserErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityErrorResponseWriterTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SecurityErrorResponseWriter writer = new SecurityErrorResponseWriter(mapper);

    @ParameterizedTest
    @MethodSource("securityErrors")
    void writesTheErrorAsUtf8JsonWithItsHttpStatusAndDisablesCaching(
            ErrorCode errorCode, int expectedStatus, String expectedCode, String expectedMessage
    ) throws IOException {
        var response = new MockHttpServletResponse();

        writer.write(response, errorCode);

        assertEquals(expectedStatus, response.getStatus());
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertEquals("UTF-8", response.getCharacterEncoding());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(response.getContentType())));

        var expected = mapper.createObjectNode()
                .put("success", false)
                .putNull("data");
        expected.putObject("error")
                .put("code", expectedCode)
                .put("message", expectedMessage)
                .putArray("details");
        assertEquals(expected, mapper.readTree(response.getContentAsString(UTF_8)));
    }

    @Test
    void replacesAnUnsentBodyWhilePreservingTheSessionCookieAndOtherHeadersOn503() throws IOException {
        var response = new MockHttpServletResponse();
        response.setHeader(HttpHeaders.SET_COOKIE, "SESSION=existing-session; Path=/; HttpOnly");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://travelpay.example");
        response.setContentLength(10000);
        response.getOutputStream().write("partial response".getBytes(UTF_8));

        writer.write(response, CommonErrorCode.SERVICE_UNAVAILABLE);

        assertEquals(503, response.getStatus());
        assertEquals("SESSION=existing-session; Path=/; HttpOnly", response.getHeader(HttpHeaders.SET_COOKIE));
        assertEquals("https://travelpay.example", response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals(response.getContentAsByteArray().length, response.getContentLength());
        assertEquals(mapper.readTree("""
                {
                  "success": false,
                  "data": null,
                  "error": {
                    "code": "COMMON_SERVICE_UNAVAILABLE",
                    "message": "서비스를 일시적으로 사용할 수 없습니다.",
                    "details": []
                  }
                }
                """), mapper.readTree(response.getContentAsString(UTF_8)));
    }

    @Test
    void leavesAnAlreadyCommittedResponseUntouched() throws IOException {
        var response = new MockHttpServletResponse();
        response.setStatus(202);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private");
        response.getOutputStream().write("already sent".getBytes(UTF_8));
        response.flushBuffer();

        writer.write(response, CommonErrorCode.UNAUTHORIZED);

        assertEquals(202, response.getStatus());
        assertEquals("private", response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertEquals("already sent", response.getContentAsString(UTF_8));
    }

    private static Stream<Arguments> securityErrors() {
        return Stream.of(
                Arguments.of(CommonErrorCode.UNAUTHORIZED, 401,
                        "COMMON_UNAUTHORIZED", "로그인이 필요합니다."),
                Arguments.of(CommonErrorCode.FORBIDDEN, 403,
                        "COMMON_FORBIDDEN", "요청을 수행할 권한이 없습니다."),
                Arguments.of(CommonErrorCode.CSRF_INVALID, 403,
                        "COMMON_CSRF_INVALID", "CSRF 토큰이 올바르지 않습니다."),
                Arguments.of(CommonErrorCode.SERVICE_UNAVAILABLE, 503,
                        "COMMON_SERVICE_UNAVAILABLE", "서비스를 일시적으로 사용할 수 없습니다."),
                Arguments.of(UserErrorCode.INVALID_CREDENTIALS, 401,
                        "USER_INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.")
        );
    }
}
