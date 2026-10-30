package com.travelpay.user.common.security.access;

import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestAuthenticationEntryPointTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestAuthenticationEntryPoint entryPoint =
            new RestAuthenticationEntryPoint(new SecurityErrorResponseWriter(mapper));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @MethodSource("authenticationFailures")
    void securityFilterReturnsSafeJsonAndPreservesCookiesInsteadOfRedirecting(
            AuthenticationException exception, int expectedStatus, String expectedCode, String expectedMessage
    ) throws Exception {
        var filter = new ExceptionTranslationFilter(entryPoint, new NullRequestCache());
        var request = new MockHttpServletRequest("GET", "/test/protected");
        request.addHeader(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE);
        var response = new MockHttpServletResponse();
        response.setHeader(HttpHeaders.SET_COOKIE, "SESSION=existing-session; Path=/; HttpOnly");

        filter.doFilter(request, response, (req, res) -> {
            throw exception;
        });

        assertEquals(expectedStatus, response.getStatus());
        assertNull(response.getRedirectedUrl());
        assertNull(response.getHeader(HttpHeaders.LOCATION));
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertEquals("SESSION=existing-session; Path=/; HttpOnly", response.getHeader(HttpHeaders.SET_COOKIE));
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

    private static Stream<Arguments> authenticationFailures() {
        return Stream.of(
                Arguments.of(new InsufficientAuthenticationException("테스트용 내부 미인증 사유"),
                        401, "COMMON_UNAUTHORIZED", "로그인이 필요합니다."),
                Arguments.of(new AuthenticationCredentialsNotFoundException("테스트용 인증 정보 누락"),
                        401, "COMMON_UNAUTHORIZED", "로그인이 필요합니다."),
                Arguments.of(new AuthenticationServiceException("테스트용 내부 인증 저장소 장애"),
                        503, "COMMON_SERVICE_UNAVAILABLE", "서비스를 일시적으로 사용할 수 없습니다."),
                Arguments.of(new InternalAuthenticationServiceException("테스트용 내부 DB 장애"),
                        503, "COMMON_SERVICE_UNAVAILABLE", "서비스를 일시적으로 사용할 수 없습니다.")
        );
    }
}
