package com.travelpay.user.common.security.session;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserSessionPrincipalTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final Instant AUTHENTICATED_AT = Instant.parse("2026-10-30T00:00:00Z");

    @ParameterizedTest
    @EnumSource(AuthRole.class)
    void restoresTheMemberIdentityAndLoginTimeFromSerializedSecurityContext(AuthRole role) {
        var principal = new UserSessionPrincipal(USER_ID, role, AUTHENTICATED_AT);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        var serializer = new JdkSerializationRedisSerializer();

        var restoredContext = assertInstanceOf(SecurityContext.class,
                serializer.deserialize(serializer.serialize(context)));
        var restored = assertInstanceOf(UserSessionPrincipal.class,
                restoredContext.getAuthentication().getPrincipal());

        assertNotSame(principal, restored);
        assertEquals(USER_ID, restored.userId());
        assertEquals(role, restored.role());
        assertEquals(AUTHENTICATED_AT, restored.authenticatedAt());
        assertEquals("550e8400-e29b-41d4-a716-446655440000",
                restoredContext.getAuthentication().getName());
    }

    @ParameterizedTest
    @CsvSource({"PT0S, false", "PT23H59M59S, false", "PT24H, true", "PT24H0M1S, true"})
    void expiresOnceTheAbsoluteTimeoutHasPassedSinceLogin(Duration elapsed, boolean expired) {
        var principal = new UserSessionPrincipal(USER_ID, AuthRole.USER, AUTHENTICATED_AT);

        assertEquals(expired, principal.isExpiredAt(AUTHENTICATED_AT.plus(elapsed), Duration.ofHours(24)));
    }

    @Test
    void rejectsAMissingMemberId() {
        assertThrows(IllegalArgumentException.class,
                () -> new UserSessionPrincipal(null, AuthRole.USER, AUTHENTICATED_AT));
    }

    @Test
    void rejectsAMissingRole() {
        assertThrows(IllegalArgumentException.class,
                () -> new UserSessionPrincipal(USER_ID, null, AUTHENTICATED_AT));
    }

    @Test
    void rejectsAMissingLoginTime() {
        assertThrows(IllegalArgumentException.class,
                () -> new UserSessionPrincipal(USER_ID, AuthRole.USER, null));
    }
}
