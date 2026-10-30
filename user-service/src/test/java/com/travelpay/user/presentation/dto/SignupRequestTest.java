package com.travelpay.user.presentation.dto;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import com.travelpay.user.domain.model.vo.TermsDocumentId;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignupRequestTest {

    private static final String EMAIL = "member@example.com";
    private static final String PASSWORD = "TravelpayDemo-2026!";
    private static final String REAL_NAME = "홍길동";
    private static final String PHONE = "010-1234-5678";

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
    void readsTheApiInputAndNormalizesIdentityValuesWithoutChangingThePassword() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> {
                    var request = context.getBean(JsonMapper.class).readValue("""
                            {
                              "loginEmail": "  Member@Example.COM  ",
                              "password": "TravelpayDemo-2026!",
                              "realName": "  홍길동  ",
                              "phone": "010-1234-5678",
                              "consents": [{"termsCode":" SERVICE_TERMS ","termsVersion":" v1 "}]
                            }
                            """, SignupRequest.class);

                    assertEquals(EMAIL, request.loginEmail());
                    assertEquals(PASSWORD, request.password());
                    assertEquals(REAL_NAME, request.realName());
                    assertEquals(PHONE, request.phone());
                    assertEquals("SERVICE_TERMS", request.consents().getFirst().termsCode());
                    assertEquals("v1", request.consents().getFirst().termsVersion());
                    assertTrue(validator.validate(request).isEmpty());
                });
    }

    @Test
    void acceptsTheStorageLengthBoundariesAndCountsUnicodeCodePointsForNames() {
        var request = new SignupRequest(emailAtLength(254), PASSWORD, "𠀀".repeat(40), PHONE,
                List.of(new SignupRequest.Consent("T".repeat(40), "V".repeat(40))));

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void convertsNormalizedInputAndEveryConsentToTheApplicationCommand() {
        var request = new SignupRequest("  SIGN.UP@EXAMPLE.COM  ", PASSWORD, "  김여행  ", "010-2222-3333",
                List.of(new SignupRequest.Consent(" SERVICE_TERMS ", " v2 "),
                        new SignupRequest.Consent("PRIVACY_TERMS", "v5"),
                        new SignupRequest.Consent("SERVICE_TERMS", "v2")));

        var command = request.toCommand();

        assertNotNull(command);
        assertEquals("sign.up@example.com", command.loginEmail());
        assertEquals(PASSWORD, command.rawPassword());
        assertEquals("김여행", command.realName());
        assertEquals("010-2222-3333", command.phone());
        assertEquals(List.of(new TermsDocumentId("SERVICE_TERMS", "v2"),
                new TermsDocumentId("PRIVACY_TERMS", "v5"),
                new TermsDocumentId("SERVICE_TERMS", "v2")), command.consents());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user@example.com", "a.b+tag@sub.example.co.kr", "홍길동@example.com"})
    void acceptsSupportedLoginEmailFormats(String email) {
        var request = new SignupRequest(email, PASSWORD, REAL_NAME, PHONE, validConsents());

        assertTrue(validator.validate(request).isEmpty());
    }

    @ParameterizedTest(name = "입력 {index}: {0}")
    @MethodSource("invalidRootFields")
    void reportsInvalidRootInputOnItsActualField(String field, String value) {
        var request = new SignupRequest(
                field.equals("loginEmail") ? value : EMAIL,
                field.equals("password") ? value : PASSWORD,
                field.equals("realName") ? value : REAL_NAME,
                field.equals("phone") ? value : PHONE,
                validConsents());

        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals(field)));
    }

    @ParameterizedTest(name = "약관 항목 {index}: {0}")
    @MethodSource("invalidConsentFields")
    void validatesTheCodeAndVersionInsideEachConsent(String field, String value) {
        var consent = new SignupRequest.Consent(
                field.equals("termsCode") ? value : "SERVICE_TERMS",
                field.equals("termsVersion") ? value : "v1");
        var request = new SignupRequest(EMAIL, PASSWORD, REAL_NAME, PHONE, List.of(consent));

        assertTrue(validator.validate(request).stream().anyMatch(violation ->
                violation.getPropertyPath().toString().equals("consents[0]." + field)));
    }

    @Test
    void requiresTheConsentListToBePresent() {
        var request = new SignupRequest(EMAIL, PASSWORD, REAL_NAME, PHONE, null);

        assertTrue(validator.validate(request).stream().anyMatch(violation ->
                violation.getPropertyPath().toString().equals("consents")));
    }

    @Test
    void rejectsANullConsentElement() {
        var request = new SignupRequest(EMAIL, PASSWORD, REAL_NAME, PHONE,
                Collections.singletonList(null));

        assertTrue(validator.validate(request).stream().anyMatch(violation ->
                violation.getPropertyPath().toString().startsWith("consents[0]")));
    }

    @Test
    void leavesRequiredAndCurrentConsentChecksToTheApplicationService() {
        var request = new SignupRequest(EMAIL, PASSWORD, REAL_NAME, PHONE, List.of());

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void doesNotNormalizeInvalidPasswordWhitespaceIntoAnAcceptedPassword() {
        var password = PASSWORD + " ";
        var request = new SignupRequest(EMAIL, password, REAL_NAME, PHONE, validConsents());

        assertEquals(password, request.password());
        assertTrue(validator.validate(request).stream().anyMatch(violation ->
                violation.getPropertyPath().toString().equals("password")));
    }

    @Test
    void stringRepresentationsDoNotExposePersonalInputOrThePassword() {
        var request = new SignupRequest(EMAIL, PASSWORD, REAL_NAME, PHONE, validConsents());
        var text = request.toString();

        for (var sensitiveValue : List.of(EMAIL, PASSWORD, REAL_NAME, PHONE)) {
            assertFalse(text.contains(sensitiveValue));
        }
    }

    private static List<SignupRequest.Consent> validConsents() {
        return List.of(new SignupRequest.Consent("SERVICE_TERMS", "v1"));
    }

    private static String emailAtLength(int length) {
        return "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "."
                + "d".repeat(length - 197) + ".com";
    }

    private static Stream<Arguments> invalidRootFields() {
        return Stream.of(
                Arguments.of("loginEmail", null),
                Arguments.of("loginEmail", "   "),
                Arguments.of("loginEmail", "not-an-email"),
                Arguments.of("loginEmail", "\"a b\"@example.com"),
                Arguments.of("loginEmail", "\"a@b\"@example.com"),
                Arguments.of("loginEmail", "a" + Character.toString(0x0085) + "b@example.com"),
                Arguments.of("loginEmail", emailAtLength(255)),
                Arguments.of("password", null),
                Arguments.of("password", "short1!"),
                Arguments.of("realName", null),
                Arguments.of("realName", "   "),
                Arguments.of("realName", "𠀀".repeat(41)),
                Arguments.of("realName", "홍" + Character.toString(10) + "길동"),
                Arguments.of("phone", null),
                Arguments.of("phone", "01012345678"),
                Arguments.of("phone", "011-1234-5678"),
                Arguments.of("phone", "010-123-5678")
        );
    }

    private static Stream<Arguments> invalidConsentFields() {
        return Stream.of(
                Arguments.of("termsCode", null),
                Arguments.of("termsCode", "   "),
                Arguments.of("termsCode", "T".repeat(41)),
                Arguments.of("termsCode", "SERVICE" + Character.toString(10) + "TERMS"),
                Arguments.of("termsVersion", null),
                Arguments.of("termsVersion", "   "),
                Arguments.of("termsVersion", "V".repeat(41)),
                Arguments.of("termsVersion", "v" + Character.toString(0) + "1")
        );
    }
}
