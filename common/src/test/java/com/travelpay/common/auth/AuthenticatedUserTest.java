package com.travelpay.common.auth;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthenticatedUserTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void acceptsACompleteIdentity() {
        assertDoesNotThrow(() -> new AuthenticatedUser(USER_ID, AuthRole.USER));
    }

    @Test
    void rejectsAnIdentityWithoutAUserId() {
        assertThrows(NullPointerException.class, () -> new AuthenticatedUser(null, AuthRole.USER));
    }

    @Test
    void rejectsAnIdentityWithoutARole() {
        assertThrows(NullPointerException.class, () -> new AuthenticatedUser(USER_ID, null));
    }
}
