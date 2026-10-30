package com.travelpay.user.common.security.access;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestAccessDeniedHandlerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SecurityErrorResponseWriter writer = new SecurityErrorResponseWriter(mapper);
    private final RestAccessDeniedHandler handler = new RestAccessDeniedHandler(writer);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @MethodSource("csrfFailures")
    void csrfFilterReturnsTheCommonCsrfErrorForMissingOrInvalidTokens(
            boolean hasStoredToken, String submittedToken
    ) throws Exception {
        var repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("Csrf-Token");
        var filter = new CsrfFilter(repository);
        filter.setAccessDeniedHandler(handler);
        var request = new MockHttpServletRequest("POST", "/test/protected");
        var response = new MockHttpServletResponse();

        if (hasStoredToken) {
            repository.saveToken(new DefaultCsrfToken("Csrf-Token", "_csrf", "server-csrf-token-for-test"),
                    request, response);
        }
        if (submittedToken != null) {
            request.addHeader("Csrf-Token", submittedToken);
        }

        filter.doFilter(request, response, (req, res) -> {
        });

        assertForbiddenJson(response, "COMMON_CSRF_INVALID", "CSRF 토큰이 올바르지 않습니다.");
    }

    @Test
    void authenticatedAccessDenialReturnsThePermissionError() throws Exception {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "test-user", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var filter = new ExceptionTranslationFilter(
                new RestAuthenticationEntryPoint(writer), new NullRequestCache());
        filter.setAccessDeniedHandler(handler);
        var request = new MockHttpServletRequest("GET", "/test/admin-only");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AccessDeniedException("테스트용 내부 권한 거절 사유");
        });

        assertForbiddenJson(response, "COMMON_FORBIDDEN", "요청을 수행할 권한이 없습니다.");
    }

    private void assertForbiddenJson(MockHttpServletResponse response, String expectedCode, String expectedMessage)
            throws IOException {
        assertEquals(403, response.getStatus());
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertNull(response.getRedirectedUrl());
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

    private static Stream<Arguments> csrfFailures() {
        return Stream.of(
                Arguments.of(false, null),
                Arguments.of(true, null),
                Arguments.of(true, "invalid-csrf-token-for-test")
        );
    }
}
