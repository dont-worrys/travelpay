package com.travelpay.user.common.security.session;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCurrentUserProviderTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final Instant AUTHENTICATED_AT = Instant.parse("2026-10-30T00:00:00Z");

    private final SessionCurrentUserProvider provider = new SessionCurrentUserProvider();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(AuthRole.class)
    void returnsTheMemberIdAndRoleFromAnAuthenticatedSessionPrincipal(AuthRole role) {
        var principal = new UserSessionPrincipal(USER_ID, role, AUTHENTICATED_AT);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));

        var user = provider.requireCurrentUser();

        assertEquals(USER_ID, user.userId());
        assertEquals(role, user.role());
    }

    @Test
    void returnsEmptyWhenThereIsNoAuthentication() {
        assertTrue(provider.findCurrentUser().isEmpty());
    }

    @Test
    void rejectsAnUnauthenticatedTokenEvenWhenItContainsAMemberPrincipal() {
        var principal = new UserSessionPrincipal(USER_ID, AuthRole.USER, AUTHENTICATED_AT);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated(principal, null));

        assertTrue(provider.findCurrentUser().isEmpty());
    }

    @Test
    void rejectsAnAnonymousTokenEvenWhenItsAuthenticatedFlagAndPrincipalLookValid() {
        var principal = new UserSessionPrincipal(USER_ID, AuthRole.USER, AUTHENTICATED_AT);
        var anonymous = new AnonymousAuthenticationToken("anonymous-key-for-test", principal,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(anonymous);
        assertTrue(anonymous.isAuthenticated());

        assertTrue(provider.findCurrentUser().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("nonMemberPrincipals")
    void doesNotInterpretOtherAuthenticatedPrincipalTypesAsRegisteredMembers(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertTrue(provider.findCurrentUser().isEmpty());
    }

    @Test
    void requiredLookupUsesTheCommonUnauthorizedErrorWhenThereIsNoMember() {
        var exception = assertThrows(BusinessException.class, provider::requireCurrentUser);

        assertEquals(CommonErrorCode.UNAUTHORIZED, exception.getErrorCode());
    }

    private static Stream<Arguments> nonMemberPrincipals() {
        return Stream.of(
                Arguments.of("member@example.com"),
                Arguments.of(USER_ID.toString()),
                Arguments.of(Map.of("sub", "external-social-subject")),
                Arguments.of(new AuthenticatedUser(USER_ID, AuthRole.USER)),
                Arguments.of((Object) null)
        );
    }
}
