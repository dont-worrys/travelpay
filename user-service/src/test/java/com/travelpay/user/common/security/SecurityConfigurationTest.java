package com.travelpay.user.common.security;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.user.application.service.CurrentMemberService;
import com.travelpay.user.common.security.access.RestAccessDeniedHandler;
import com.travelpay.user.common.security.access.RestAuthenticationEntryPoint;
import com.travelpay.user.common.security.access.SecurityErrorResponseWriter;
import com.travelpay.user.common.security.session.UserSessionPrincipal;
import com.travelpay.user.presentation.controller.CsrfController;
import com.travelpay.user.presentation.dto.CsrfTokenResponse;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(classes = {
        SecurityConfiguration.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class,
        CsrfController.class,
        SecurityConfigurationTest.WebTestConfiguration.class
})
class SecurityConfigurationTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID MASTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JsonMapper mapper;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void passwordEncoderCreatesVersionedHashesAndRejectsIncorrectPasswords() {
        var encoder = context.getBean(PasswordEncoder.class);
        var rawPassword = "TestPassword!2026";

        var encodedPassword = encoder.encode(rawPassword);

        assertNotEquals(rawPassword, encodedPassword);
        assertTrue(encodedPassword.startsWith("{bcrypt}"));
        assertTrue(encoder.matches(rawPassword, encodedPassword));
        assertFalse(encoder.matches("DifferentPassword!2026", encodedPassword));
    }

    @Test
    void passwordEncoderUsesADifferentSaltForEachHash() {
        var encoder = context.getBean(PasswordEncoder.class);
        var rawPassword = "TestPassword!2026";

        var firstHash = encoder.encode(rawPassword);
        var secondHash = encoder.encode(rawPassword);

        assertNotEquals(firstHash, secondHash);
        assertTrue(encoder.matches(rawPassword, firstHash));
        assertTrue(encoder.matches(rawPassword, secondHash));
    }

    @Test
    void anonymousClientCanReadTheCsrfHeaderNameAndTokenWithoutCaching() throws Exception {
        var result = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString(UTF_8));
        var token = mapper.treeToValue(body.get("data"), CsrfTokenResponse.class);

        assertEquals(mapper.readTree("true"), body.get("success"));
        assertTrue(body.get("error").isNull());
        assertEquals("Csrf-Token", token.headerName());
        assertFalse(token.token().isBlank());
        assertNotNull(result.getRequest().getSession(false));
        var cacheControl = result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL);
        assertNotNull(cacheControl);
        assertTrue(cacheControl.contains("no-store"), cacheControl);
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(
                MediaType.parseMediaType(result.getResponse().getContentType())));
    }

    @Test
    void anonymousClientCanReadTerms() throws Exception {
        mvc.perform(get("/api/v1/terms")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/users", "/api/v1/auth/login"})
    void publicMutationStillRequiresCsrf(String path) throws Exception {
        var result = mvc.perform(post(path)).andReturn();

        assertError(result, 403, "COMMON_CSRF_INVALID", "CSRF 토큰이 올바르지 않습니다.");
    }

    @ParameterizedTest
    @CsvSource({"/api/v1/users,201", "/api/v1/auth/login,200"})
    void publicMutationAcceptsTheTokenReturnedByTheCsrfEndpoint(String path, int expectedStatus) throws Exception {
        var csrf = fetchCsrf(null);

        mvc.perform(post(path).session(csrf.session()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void csrfTokenFromOneSessionCannotAuthorizeAnotherSession() throws Exception {
        var first = fetchCsrf(null);
        var second = fetchCsrf(null);

        mvc.perform(post("/api/v1/users").session(first.session()).header(first.headerName(), first.token()))
                .andExpect(status().isCreated());

        var result = mvc.perform(post("/api/v1/users")
                        .session(second.session()).header(first.headerName(), first.token()))
                .andReturn();

        assertError(result, 403, "COMMON_CSRF_INVALID", "CSRF 토큰이 올바르지 않습니다.");
    }

    @Test
    void identityHeadersCannotAuthenticateAProtectedReadOrTriggerALoginRedirect() throws Exception {
        var result = mvc.perform(get("/api/v1/users/me")
                        .accept(MediaType.TEXT_HTML)
                        .header("User-Id", "550e8400-e29b-41d4-a716-446655440000")
                        .header("User-Role", "MASTER"))
                .andReturn();

        assertError(result, 401, "COMMON_UNAUTHORIZED", "로그인이 필요합니다.");
        assertNull(result.getRequest().getSession(false));
    }

    @Test
    void authenticatedSessionCanAccessAProtectedRead() throws Exception {
        mvc.perform(get("/api/v1/users/me").session(authenticatedSession("ROLE_USER")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({"ROLE_USER,403", "ROLE_MASTER,200"})
    void adminRoutesRequireTheMasterRole(String authority, int expectedStatus) throws Exception {
        var result = mvc.perform(get("/api/v1/admin/users").session(authenticatedSession(authority)))
                .andReturn();

        if (expectedStatus == 403) {
            assertError(result, 403, "COMMON_FORBIDDEN", "요청을 수행할 권한이 없습니다.");
        }
        else {
            assertEquals(200, result.getResponse().getStatus());
        }
    }

    @Test
    void authenticatedMutationStillRequiresCsrf() throws Exception {
        var result = mvc.perform(patch("/api/v1/users/me/password")
                        .session(authenticatedSession("ROLE_USER")))
                .andReturn();

        assertError(result, 403, "COMMON_CSRF_INVALID", "CSRF 토큰이 올바르지 않습니다.");
    }

    @Test
    void authenticatedMutationAcceptsTheReturnedCsrfToken() throws Exception {
        var csrf = fetchCsrf(authenticatedSession("ROLE_USER"));

        mvc.perform(patch("/api/v1/users/me/password")
                        .session(csrf.session()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isOk());
    }

    @Test
    void authenticatedMutationRejectsAForgedCsrfToken() throws Exception {
        var csrf = fetchCsrf(authenticatedSession("ROLE_USER"));
        var result = mvc.perform(patch("/api/v1/users/me/password")
                        .session(csrf.session()).header(csrf.headerName(), "forged-csrf-token-for-test"))
                .andReturn();

        assertError(result, 403, "COMMON_CSRF_INVALID", "CSRF 토큰이 올바르지 않습니다.");
    }

    @Test
    void mutationRequiresTheConfiguredCsrfHeaderName() throws Exception {
        var csrf = fetchCsrf(authenticatedSession("ROLE_USER"));
        var result = mvc.perform(patch("/api/v1/users/me/password")
                        .session(csrf.session()).header("X-CSRF-TOKEN", csrf.token()))
                .andReturn();

        assertError(result, 403, "COMMON_CSRF_INVALID", "CSRF 토큰이 올바르지 않습니다.");
    }

    @Test
    void validCsrfTokenDoesNotAuthenticateAProtectedMutation() throws Exception {
        var csrf = fetchCsrf(null);
        var result = mvc.perform(patch("/api/v1/users/me/password")
                        .session(csrf.session()).header(csrf.headerName(), csrf.token()))
                .andReturn();

        assertError(result, 401, "COMMON_UNAUTHORIZED", "로그인이 필요합니다.");
    }

    @Test
    void authPathPrefixDoesNotMakeUnlistedRoutesPublic() throws Exception {
        var result = mvc.perform(get("/api/v1/auth/unlisted")).andReturn();

        assertError(result, 401, "COMMON_UNAUTHORIZED", "로그인이 필요합니다.");
    }

    @Test
    void serverErrorDispatchCanReachErrorHandlingWithoutMakingThePathPublic() throws Exception {
        var direct = mvc.perform(get("/test/error-dispatch")).andReturn();
        assertError(direct, 401, "COMMON_UNAUTHORIZED", "로그인이 필요합니다.");

        mvc.perform(get("/test/error-dispatch").with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    return request;
                }))
                .andExpect(status().isOk());
    }

    @Test
    void defaultFrameworkLoginPageIsNotEnabled() throws Exception {
        var result = mvc.perform(get("/login").accept(MediaType.TEXT_HTML)).andReturn();

        assertError(result, 401, "COMMON_UNAUTHORIZED", "로그인이 필요합니다.");
    }

    private CsrfClientState fetchCsrf(MockHttpSession session) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString(UTF_8));
        var token = mapper.treeToValue(body.get("data"), CsrfTokenResponse.class);
        return new CsrfClientState((MockHttpSession) result.getRequest().getSession(false),
                token.headerName(), token.token());
    }

    private MockHttpSession authenticatedSession(String authority) {
        var role = authority.equals("ROLE_MASTER") ? AuthRole.MASTER : AuthRole.USER;
        var userId = role == AuthRole.MASTER ? MASTER_ID : USER_ID;
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserSessionPrincipal(userId, role, Instant.parse("2026-10-30T00:00:00Z")),
                null, List.of(new SimpleGrantedAuthority(authority))));
        var session = new MockHttpSession(context.getServletContext());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        return session;
    }

    private void assertError(MvcResult result, int status, String code, String message) throws IOException {
        var response = result.getResponse();
        assertEquals(status, response.getStatus());
        assertNull(response.getRedirectedUrl());
        var expected = mapper.createObjectNode().put("success", false).putNull("data");
        expected.putObject("error").put("code", code).put("message", message).putArray("details");
        assertEquals(expected, mapper.readTree(response.getContentAsString(UTF_8)));
    }

    private record CsrfClientState(MockHttpSession session, String headerName, String token) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    static class WebTestConfiguration {

        /** 웹 보안 단위 시험의 회원 조회 대역이며 실제 DB 판단은 별도 통합 경로에서 확인합니다. */
        @Bean
        CurrentMemberService currentMemberService() {
            var service = mock(CurrentMemberService.class);
            when(service.requireActive(any(UUID.class))).thenAnswer(invocation -> {
                var userId = invocation.getArgument(0, UUID.class);
                return new AuthenticatedUser(userId, userId.equals(MASTER_ID) ? AuthRole.MASTER : AuthRole.USER);
            });
            return service;
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    /** 보안 설정 검증용이며 실제 User API 구현이 아닙니다. */
    @TestComponent
    @RestController
    static class ProbeController {

        @GetMapping({"/api/v1/terms", "/api/v1/users/me", "/api/v1/admin/users", "/test/error-dispatch"})
        ApiResponse<Map<String, String>> readProbe() {
            return ApiResponse.success(Map.of("probe", "read"));
        }

        @PostMapping("/api/v1/users")
        @ResponseStatus(HttpStatus.CREATED)
        ApiResponse<Void> signupProbe() {
            return ApiResponse.success(null);
        }

        @PostMapping("/api/v1/auth/login")
        ApiResponse<Void> loginProbe() {
            return ApiResponse.success(null);
        }

        @PatchMapping("/api/v1/users/me/password")
        ApiResponse<Void> passwordProbe() {
            return ApiResponse.success(null);
        }
    }
}
