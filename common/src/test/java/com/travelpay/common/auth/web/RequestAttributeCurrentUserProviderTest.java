package com.travelpay.common.auth.web;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequestAttributeCurrentUserProviderTest {

    private static final AuthenticatedUser FIRST_USER = new AuthenticatedUser(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), AuthRole.USER);
    private static final AuthenticatedUser SECOND_USER = new AuthenticatedUser(
            UUID.fromString("4c6a0178-266b-4f1e-b811-a2767026be70"), AuthRole.MASTER);

    private final RequestAttributeCurrentUserProvider provider = new RequestAttributeCurrentUserProvider();

    @BeforeEach
    void startWithoutARequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void returnsEmptyOutsideARequest() {
        assertEquals(Optional.empty(), provider.findCurrentUser());
    }

    @Test
    void returnsTheIdentityStoredInTheCurrentRequest() {
        bind(requestWith(FIRST_USER));

        assertEquals(Optional.of(FIRST_USER), provider.findCurrentUser());
    }

    @Test
    void doesNotCreateAnIdentityFromHeadersOrQueryParameters() {
        var request = new MockHttpServletRequest();
        request.addHeader(AuthHeaders.USER_ID, SECOND_USER.userId().toString());
        request.addHeader(AuthHeaders.USER_ROLE, "MASTER");
        request.setParameter("userId", SECOND_USER.userId().toString());
        request.setParameter("role", "MASTER");
        bind(request);

        assertEquals(Optional.empty(), provider.findCurrentUser());
    }

    @Test
    void doesNotReadIdentityFromTheSessionScope() {
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(RequestAttributeCurrentUserProvider.CURRENT_USER_ATTRIBUTE, SECOND_USER);
        bind(request);

        assertEquals(Optional.empty(), provider.findCurrentUser());
    }

    @Test
    void rejectsAnUnexpectedStoredTypeAsAServerConfigurationError() {
        var request = new MockHttpServletRequest();
        request.setAttribute(RequestAttributeCurrentUserProvider.CURRENT_USER_ATTRIBUTE, "USER");
        bind(request);

        assertThrows(IllegalStateException.class, provider::findCurrentUser);
    }

    @Test
    void doesNotReadIdentityFromAnAlreadyCompletedRequest() {
        var attributes = bind(requestWith(FIRST_USER));
        attributes.requestCompleted();

        assertThrows(IllegalStateException.class, provider::findCurrentUser);
    }

    @Test
    void theSameProviderFollowsTheCurrentRequestWithoutKeepingThePreviousUser() {
        bind(requestWith(FIRST_USER));
        assertEquals(Optional.of(FIRST_USER), provider.findCurrentUser());

        bind(requestWith(SECOND_USER));
        assertEquals(Optional.of(SECOND_USER), provider.findCurrentUser());

        RequestContextHolder.resetRequestAttributes();
        assertEquals(Optional.empty(), provider.findCurrentUser());
    }

    @Test
    void aBackgroundThreadWithoutARequestDoesNotReceiveTheCallingThreadsIdentity() throws Exception {
        bind(requestWith(FIRST_USER));
        assertEquals(Optional.of(FIRST_USER), provider.findCurrentUser());

        try (var executor = Executors.newSingleThreadExecutor()) {
            assertEquals(Optional.empty(), executor.submit(provider::findCurrentUser).get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void mvcInjectsTheStoredIdentityThroughTheRealProviderAndResolver() throws Exception {
        var mvc = mvc();

        mvc.perform(get("/test/current-user")
                        .requestAttr(RequestAttributeCurrentUserProvider.CURRENT_USER_ATTRIBUTE, FIRST_USER)
                        .param("userId", SECOND_USER.userId().toString())
                        .param("role", "MASTER")
                        .header(AuthHeaders.USER_ID, SECOND_USER.userId().toString())
                        .header(AuthHeaders.USER_ROLE, "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(FIRST_USER.userId().toString()))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void mvcRejectsHeadersWithoutAStoredIdentityAs401() throws Exception {
        var mvc = mvc();

        mvc.perform(get("/test/current-user")
                        .header(AuthHeaders.USER_ID, SECOND_USER.userId().toString())
                        .header(AuthHeaders.USER_ROLE, "MASTER"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    void mvcReturnsASafe500ForAnInvalidStoredType() throws Exception {
        var mvc = mvc();

        var result = mvc.perform(get("/test/current-user")
                        .requestAttr(RequestAttributeCurrentUserProvider.CURRENT_USER_ATTRIBUTE,
                                "DO_NOT_ECHO_INVALID_ATTRIBUTE"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_SERVER_ERROR"))
                .andReturn();

        assertFalse(result.getResponse().getContentAsString(UTF_8).contains("DO_NOT_ECHO"));
    }

    private static MockHttpServletRequest requestWith(AuthenticatedUser user) {
        var request = new MockHttpServletRequest();
        request.setAttribute(RequestAttributeCurrentUserProvider.CURRENT_USER_ATTRIBUTE, user);
        return request;
    }

    private static ServletRequestAttributes bind(MockHttpServletRequest request) {
        var attributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attributes);
        return attributes;
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(provider))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @RestController
    static class ProbeController {

        @GetMapping("/test/current-user")
        AuthenticatedUser currentUser(@CurrentUser AuthenticatedUser user) {
            return user;
        }
    }
}
