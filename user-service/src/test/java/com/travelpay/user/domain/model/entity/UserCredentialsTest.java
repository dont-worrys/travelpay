package com.travelpay.user.domain.model.entity;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserCredentialsTest {

    private static final Instant PASSWORD_SET_AT = Instant.parse("2026-10-29T00:00:00Z");
    private static final String PASSWORD_HASH =
            "{bcrypt}$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG";

    @Test
    void creationNormalizesTheLoginEmailAndPreservesTheEncodedPassword() {
        var user = user();

        var credentials = UserCredentials.createFor(user, "  Member@Example.COM  ", PASSWORD_HASH, PASSWORD_SET_AT);

        assertSame(user, credentials.getUser());
        assertEquals("member@example.com", credentials.getLoginEmail());
        assertEquals(PASSWORD_HASH, credentials.getPasswordHash());
        assertEquals(PASSWORD_SET_AT, credentials.getPasswordChangedAt());
        assertNull(credentials.getCreatedAt());
    }

    @Test
    void creationAcceptsAnEmailAtTheColumnLengthLimit() {
        var email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);

        var credentials = UserCredentials.createFor(user(), email, PASSWORD_HASH, PASSWORD_SET_AT);

        assertEquals(254, credentials.getLoginEmail().length());
        assertEquals(email, credentials.getLoginEmail());
    }

    @ParameterizedTest
    @MethodSource("invalidEmails")
    void creationRejectsMissingMalformedOrOverlongLoginEmails(String email) {
        assertThrows(IllegalArgumentException.class,
                () -> UserCredentials.createFor(user(), email, PASSWORD_HASH, PASSWORD_SET_AT));
    }

    @ParameterizedTest
    @MethodSource("invalidPasswordHashes")
    void creationRejectsPlaintextMissingOrOverlongPasswordHashes(String passwordHash) {
        assertThrows(IllegalArgumentException.class,
                () -> UserCredentials.createFor(user(), "member@example.com", passwordHash, PASSWORD_SET_AT));
    }

    @Test
    void creationRequiresTheMember() {
        assertThrows(IllegalArgumentException.class,
                () -> UserCredentials.createFor(null, "member@example.com", PASSWORD_HASH, PASSWORD_SET_AT));
    }

    @Test
    void creationRequiresThePasswordSettingTime() {
        assertThrows(IllegalArgumentException.class,
                () -> UserCredentials.createFor(user(), "member@example.com", PASSWORD_HASH, null));
    }

    private static User user() {
        return User.register("홍길동", "010-1234-5678", PASSWORD_SET_AT);
    }

    private static Stream<String> invalidEmails() {
        return Stream.of(null, "", " ", "member", "@example.com", "member@", "member@@example.com",
                "\"a b\"@example.com", "\"a@b\"@example.com", "a" + Character.toString(0x0085) + "b@example.com",
                "mem ber@example.com", "mem\nber@example.com", "mem\u0000ber@example.com",
                "a".repeat(255) + "@example.com");
    }

    private static Stream<String> invalidPasswordHashes() {
        return Stream.of(null, "", " ", "plain-password", "{noop}plain-password", "{}hash", "{ }hash",
                "{bcrypt}", "{bcrypt} ", "{bcrypt}" + "a".repeat(248));
    }
}
