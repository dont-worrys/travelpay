package com.travelpay.user.common.validation;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidPasswordTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void maximumLengthPasswordCanBeEncodedAndPasswordComparisonRemainsCaseSensitive() {
        var password = "a".repeat(70) + "1!";
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

        assertTrue(validator.validate(new PasswordInput(password)).isEmpty());
        var hash = encoder.encode(password);

        assertTrue(encoder.matches(password, hash));
        assertFalse(encoder.matches(password.toUpperCase(Locale.ROOT), hash));
    }

    @ParameterizedTest(name = "허용 입력 {index}")
    @MethodSource("validPasswords")
    void acceptsPasswordsMeetingTheCreationPolicy(String password) {
        assertTrue(validator.validate(new PasswordInput(password)).isEmpty());
    }

    @ParameterizedTest(name = "거절 입력 {index}")
    @MethodSource("invalidPasswords")
    void rejectsInvalidPasswordsOnTheActualPasswordField(String password) {
        var violations = validator.validate(new PasswordInput(password));

        assertFalse(violations.isEmpty());
        assertEquals(Set.of("password"), violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet()));
    }

    private static Stream<String> validPasswords() {
        return Stream.of(
                "abcdefgh1!",
                "ABCDEFGH1!",
                "Abcdefgh1!",
                "abcdefghi0_",
                "a".repeat(70) + "1!"
        );
    }

    private static Stream<String> invalidPasswords() {
        return Stream.of(
                null,
                "",
                "          ",
                "abcdefg1!",
                "abcdefghij",
                "1234567890",
                "abcdefghi1",
                "abcdefghi!",
                "123456789!",
                "가나다라마바사아자차1!",
                "가".repeat(22) + "abc1!x",
                "😀".repeat(17) + "ab1!",
                "abcdefgh1!가",
                "abcdefgh1!😀",
                "abcdefgh1! ",
                " abcdefgh1!",
                "abcd efgh1!",
                "a".repeat(71) + "1!",
                "가".repeat(23) + "ab1!",
                "😀".repeat(18) + "ab1!",
                "abcdefgh1!" + Character.toString(0),
                "abcdefgh1!" + Character.toString(10),
                "abcdefgh1!" + Character.toString(0xD800),
                "abcdefgh1!" + Character.toString(0xDC00)
        );
    }

    private record PasswordInput(@ValidPassword String password) {
    }
}
