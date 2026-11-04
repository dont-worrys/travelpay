package com.travelpay.user.common.security.session;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.error.BusinessException;
import com.travelpay.user.common.error.UserErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class SessionPasswordChangeHandlerTest {

    private static final Instant LOGIN_AT = Instant.parse("2026-10-30T00:00:00Z");
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final HttpSessionCsrfTokenRepository tokens = new HttpSessionCsrfTokenRepository();
    private final SessionPasswordChangeHandler handler = new SessionPasswordChangeHandler(contexts, tokens);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @EnumSource(AuthRole.class)
    void changesSessionIdAndClearsCsrfWithoutResettingTheAuthenticatedIdentity(AuthRole role) {
        var principal = authenticate(role);
        var session = request.getSession(false);
        var oldId = session.getId();
        tokens.saveToken(new DefaultCsrfToken("Csrf-Token", "_csrf", "initial-token"), request, response);

        handler.onPasswordChangeSuccess(request, response);

        assertNotEquals(oldId, session.getId());
        assertNull(tokens.loadToken(request));
        var stored = contexts.loadDeferredContext(request).get().getAuthentication();
        assertEquals(principal.userId(), ((UserSessionPrincipal) stored.getPrincipal()).userId());
        assertEquals(role, ((UserSessionPrincipal) stored.getPrincipal()).role());
        assertEquals(LOGIN_AT, ((UserSessionPrincipal) stored.getPrincipal()).authenticatedAt());
        assertTrue(Boolean.TRUE.equals(request.getAttribute(SessionPasswordChangeHandler.ACCOUNT_UPDATED_ATTRIBUTE)));
    }

    @Test
    void aMissingExistingSessionDoesNotCreateANewLoginAfterTheAccountWasUpdated() {
        var principal = new UserSessionPrincipal(UUID.randomUUID(), AuthRole.USER, LOGIN_AT);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);

        var failure = assertThrows(BusinessException.class, () -> handler.onPasswordChangeSuccess(request, response));

        assertEquals(UserErrorCode.ACCOUNT_UPDATED_SESSION_UNAVAILABLE, failure.getErrorCode());
        assertNull(request.getSession(false));
        assertNull(response.getHeader("Set-Cookie"));
    }

    @Test
    void anAnonymousAuthenticationCannotBeSavedAsAMemberAfterTheUpdate() {
        authenticate(AuthRole.USER);
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "fixture", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        var failure = assertThrows(BusinessException.class, () -> handler.onPasswordChangeSuccess(request, response));

        assertEquals(UserErrorCode.ACCOUNT_UPDATED_SESSION_UNAVAILABLE, failure.getErrorCode());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void aContextSaveFailureReportsTheCompletedAccountUpdateWithoutExpiringTheCookie() {
        authenticate(AuthRole.USER);
        var failingContexts = mock(SecurityContextRepository.class);
        var storageFailure = new RedisConnectionFailureException("session storage unavailable");
        doThrow(storageFailure).when(failingContexts).saveContext(any(), any(), any());
        var failingHandler = new SessionPasswordChangeHandler(failingContexts, tokens);

        var failure = assertThrows(BusinessException.class,
                () -> failingHandler.onPasswordChangeSuccess(request, response));

        assertEquals(UserErrorCode.ACCOUNT_UPDATED_SESSION_UNAVAILABLE, failure.getErrorCode());
        assertSame(storageFailure, failure.getCause());
        assertTrue(Boolean.TRUE.equals(request.getAttribute(SessionPasswordChangeHandler.ACCOUNT_UPDATED_ATTRIBUTE)));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(response.getHeader("Set-Cookie"));
    }

    private UserSessionPrincipal authenticate(AuthRole role) {
        var principal = new UserSessionPrincipal(UUID.randomUUID(), role, LOGIN_AT);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
        SecurityContextHolder.setContext(context);
        var session = request.getSession(true);
        request.setRequestedSessionId(session.getId());
        request.setRequestedSessionIdValid(true);
        contexts.saveContext(context, request, response);
        return principal;
    }
}
