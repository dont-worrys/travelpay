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

class PasswordChangeRequestTest {

    private static final String CURRENT_PASSWORD = "CurrentPassword9!";
    private static final String NEW_PASSWORD = "ChangedPassword8!";
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
    void readsTheTwoPasswordFieldsFromJsonWithoutChangingTheirValues() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> {
                    var request = context.getBean(JsonMapper.class).readValue("""
                            {
                              "currentPassword": "CurrentPassword9!",
                              "newPassword": "ChangedPassword8!"
                            }
                            """, PasswordChangeRequest.class);

                    assertEquals(CURRENT_PASSWORD, request.currentPassword());
                    assertEquals(NEW_PASSWORD, request.newPassword());
                    assertTrue(validator.validate(request).isEmpty());
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "1234567890", "Abc123!"})
    void currentPasswordDoesNotNeedToMeetTheNewPasswordStrengthPolicy(String password) {
        assertTrue(validator.validate(new PasswordChangeRequest(password, NEW_PASSWORD)).isEmpty());
    }

    @Test
    void acceptsBothPasswordsAtTheSupportedLengthLimit() {
        var request = new PasswordChangeRequest("a".repeat(72), "a".repeat(70) + "1!");

        assertTrue(validator.validate(request).isEmpty());
    }

    @ParameterizedTest(name = "입력 {index}: {0}")
    @MethodSource("invalidFields")
    void reportsInvalidInputOnItsActualField(String field, String value) {
        var request = new PasswordChangeRequest(
                field.equals("currentPassword") ? value : CURRENT_PASSWORD,
                field.equals("newPassword") ? value : NEW_PASSWORD);

        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals(field)));
    }

    @Test
    void passesUnmodifiedPasswordsAndClientIpToTheRedactedApplicationCommand() {
        var current = " " + CURRENT_PASSWORD + " ";
        var next = " " + NEW_PASSWORD + " ";
        var request = new PasswordChangeRequest(current, next);

        var command = request.toCommand(CLIENT_IP);

        assertEquals(current, request.currentPassword());
        assertEquals(next, request.newPassword());
        assertEquals(current, command.currentRawPassword());
        assertEquals(next, command.newRawPassword());
        assertEquals(CLIENT_IP, command.clientIp());
        assertFalse(command.toString().contains(CURRENT_PASSWORD));
        assertFalse(command.toString().contains(NEW_PASSWORD));
        assertFalse(command.toString().contains(CLIENT_IP));
    }

    @Test
    void stringRepresentationDoesNotExposeEitherPassword() {
        var request = new PasswordChangeRequest(CURRENT_PASSWORD, NEW_PASSWORD);

        assertFalse(request.toString().contains(CURRENT_PASSWORD));
        assertFalse(request.toString().contains(NEW_PASSWORD));
    }

    private static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("currentPassword", null),
                Arguments.of("currentPassword", "   "),
                Arguments.of("currentPassword", "a".repeat(73)),
                Arguments.of("currentPassword", "가Abc12345!"),
                Arguments.of("currentPassword", " " + CURRENT_PASSWORD + " "),
                Arguments.of("currentPassword", CURRENT_PASSWORD + Character.toString(0)),
                Arguments.of("newPassword", null),
                Arguments.of("newPassword", "Abc1234!x"),
                Arguments.of("newPassword", "a".repeat(71) + "1!"),
                Arguments.of("newPassword", "Abcdefgh12"),
                Arguments.of("newPassword", "가Abc12345!")
        );
    }
}
