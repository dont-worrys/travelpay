package com.travelpay.user.presentation.dto;

import java.util.stream.Stream;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRequestTest {

    private static final String EMAIL = "member@example.com";
    private static final String PASSWORD = "TravelpayDemo-2026!";
    private static final String CLIENT_IP = "203.0.113.10";

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
    void readsLoginJsonAndNormalizesTheEmailWithoutChangingThePassword() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> {
                    var request = context.getBean(JsonMapper.class).readValue("""
                            {
                              "loginEmail": "  Member@Example.COM  ",
                              "password": "TravelpayDemo-2026!"
                            }
                            """, LoginRequest.class);

                    assertEquals(EMAIL, request.loginEmail());
                    assertEquals(PASSWORD, request.password());
                    assertTrue(validator.validate(request).isEmpty());
                });
    }

    @Test
    void acceptsTheSupportedEmailAndPasswordLengthBoundaries() {
        var request = new LoginRequest(emailAtLength(254), "a".repeat(70) + "1!");

        assertTrue(validator.validate(request).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user@example.com", "a.b+tag@sub.example.co.kr", "홍길동@example.com"})
    void acceptsTheSameSupportedEmailFormatsAsSignup(String email) {
        assertTrue(validator.validate(new LoginRequest(email, PASSWORD)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "1234567890", "abcdefghij", "Abc123!"})
    void leavesPasswordMatchingToLoginInsteadOfReapplyingCreationStrengthRules(String password) {
        assertTrue(validator.validate(new LoginRequest(EMAIL, password)).isEmpty());
    }

    @ParameterizedTest(name = "입력 {index}: {0}")
    @MethodSource("invalidFields")
    void reportsInvalidInputOnItsActualField(String field, String value) {
        var request = new LoginRequest(
                field.equals("loginEmail") ? value : EMAIL,
                field.equals("password") ? value : PASSWORD);

        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals(field)));
    }

    @Test
    void preservesPasswordWhitespaceSoItIsRejectedInsteadOfSilentlyRemoved() {
        var password = " " + PASSWORD + " ";
        var request = new LoginRequest(EMAIL, password);

        assertEquals(password, request.password());
        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("password")));
    }

    @Test
    void stringRepresentationDoesNotExposeTheEmailOrPassword() {
        var request = new LoginRequest(EMAIL, PASSWORD);

        assertFalse(request.toString().contains(EMAIL));
        assertFalse(request.toString().contains(PASSWORD));
    }

    @Test
    void passesTheNormalizedEmailUnchangedPasswordAndClientIpToARedactedApplicationCommand() {
        var command = new LoginRequest("  Member@Example.COM  ", PASSWORD).toCommand(CLIENT_IP);

        assertEquals(EMAIL, command.loginEmail());
        assertEquals(PASSWORD, command.rawPassword());
        assertEquals(CLIENT_IP, command.clientIp());
        assertFalse(command.toString().contains(EMAIL));
        assertFalse(command.toString().contains(PASSWORD));
        assertFalse(command.toString().contains(CLIENT_IP));
    }

    private static String emailAtLength(int length) {
        return "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "."
                + "d".repeat(length - 197) + ".com";
    }

    private static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("loginEmail", null),
                Arguments.of("loginEmail", "   "),
                Arguments.of("loginEmail", "not-an-email"),
                Arguments.of("loginEmail", "\"a b\"@example.com"),
                Arguments.of("loginEmail", "\"a@b\"@example.com"),
                Arguments.of("loginEmail", "a" + Character.toString(0x0085) + "b@example.com"),
                Arguments.of("loginEmail", emailAtLength(255)),
                Arguments.of("password", null),
                Arguments.of("password", ""),
                Arguments.of("password", "   "),
                Arguments.of("password", "a".repeat(71) + "1!"),
                Arguments.of("password", "가".repeat(23) + "ab1!"),
                Arguments.of("password", "😀".repeat(18) + "ab1!"),
                Arguments.of("password", PASSWORD + Character.toString(10)),
                Arguments.of("password", PASSWORD + Character.toString(0)),
                Arguments.of("password", "Travel pay-2026!")
        );
    }
}
