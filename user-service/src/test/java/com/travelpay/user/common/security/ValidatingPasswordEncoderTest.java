package com.travelpay.user.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatingPasswordEncoderTest {

    private static final String PASSWORD = "TravelpayDemo-2026!";
    private final ValidatingPasswordEncoder encoder =
            new ValidatingPasswordEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder());

    @ParameterizedTest
    @ValueSource(strings = {"2a", "2b", "2y"})
    void validBcryptRepresentationsStillVerifyThePassword(String version) {
        var hash = encoder.encode(PASSWORD).replace("$2a$", "$" + version + "$");

        assertTrue(encoder.matches(PASSWORD, hash));
        assertFalse(encoder.matches("WrongPassword-2026!", hash));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{bcrypt}broken", "{bcrypt}", "{bcrypt}$2a$10$truncated-bcrypt-hash"})
    void malformedStoredBcryptValuesAreErrorsRatherThanPasswordMismatches(String hash) {
        assertThrows(IllegalArgumentException.class, () -> encoder.matches(PASSWORD, hash));
    }

    @Test
    void upgradeChecksAlsoRejectMalformedBcryptValues() {
        assertThrows(IllegalArgumentException.class, () -> encoder.upgradeEncoding("{bcrypt}broken"));
    }

    @Test
    void prohibitedPlaintextStorageCannotBeUsedAsAValidExistingPassword() {
        assertThrows(IllegalArgumentException.class, () -> encoder.matches(PASSWORD, "{noop}" + PASSWORD));
    }

    @Test
    void otherAlreadySupportedVersionedEncodersRemainUsable() {
        var hash = "{pbkdf2@SpringSecurity_v5_8}" + Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD);

        assertTrue(encoder.matches(PASSWORD, hash));
    }
}
