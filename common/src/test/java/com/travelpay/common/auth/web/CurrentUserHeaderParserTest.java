package com.travelpay.common.auth.web;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CurrentUserHeaderParserTest {

    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";

    @ParameterizedTest
    @EnumSource(AuthRole.class)
    void parsesTheCompleteIdentityForEachSupportedRole(AuthRole role) {
        var request = request(USER_ID, role.name());

        var result = CurrentUserHeaderParser.parse(request);

        assertEquals(Optional.of(new AuthenticatedUser(UUID.fromString(USER_ID), role)), result);
    }

    @Test
    void returnsEmptyOnlyWhenBothIdentityHeadersAreAbsent() {
        var request = new MockHttpServletRequest();
        request.addHeader(AuthHeaders.REQUEST_ID, "request-without-identity");

        assertEquals(Optional.empty(), CurrentUserHeaderParser.parse(request));
    }

    @Test
    void readsHeaderNamesWithoutDependingOnTheirLetterCase() {
        var request = new MockHttpServletRequest();
        request.addHeader("user-id", USER_ID);
        request.addHeader("USER-ROLE", "USER");

        assertEquals(Optional.of(new AuthenticatedUser(UUID.fromString(USER_ID), AuthRole.USER)),
                CurrentUserHeaderParser.parse(request));
    }

    @Test
    void acceptsUppercaseHexadecimalInACanonicalUuid() {
        var request = request(USER_ID.toUpperCase(Locale.ROOT), "USER");

        assertEquals(Optional.of(new AuthenticatedUser(UUID.fromString(USER_ID), AuthRole.USER)),
                CurrentUserHeaderParser.parse(request));
    }

    @Test
    void removesSurroundingWhitespaceFromHeaderValues() {
        var request = request(" \t" + USER_ID + "\t ", "\tUSER ");

        assertEquals(Optional.of(new AuthenticatedUser(UUID.fromString(USER_ID), AuthRole.USER)),
                CurrentUserHeaderParser.parse(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {AuthHeaders.USER_ID, AuthHeaders.USER_ROLE})
    void rejectsAnIdentityWithOnlyOneHeader(String presentHeader) {
        var request = new MockHttpServletRequest();
        request.addHeader(presentHeader, AuthHeaders.USER_ID.equals(presentHeader) ? USER_ID : "USER");

        assertUnauthorized(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void rejectsBlankIdentityValues(String blankValue) {
        assertUnauthorized(request(blankValue, "USER"));
        assertUnauthorized(request(USER_ID, blankValue));
    }

    @Test
    void rejectsBothBlankHeadersInsteadOfTreatingThemAsAbsent() {
        assertUnauthorized(request("", " "));
    }

    @ParameterizedTest
    @ValueSource(strings = {AuthHeaders.USER_ID, AuthHeaders.USER_ROLE})
    void rejectsRepeatedHeadersEvenWhenOneValueWouldBeValid(String repeatedHeader) {
        var request = request(USER_ID, "USER");
        request.addHeader(repeatedHeader, AuthHeaders.USER_ID.equals(repeatedHeader) ? USER_ID : "MASTER");

        assertUnauthorized(request);
    }

    @Test
    void rejectsRepeatedIdentityHeadersWithDifferentLetterCase() {
        var request = request(USER_ID, "USER");
        request.addHeader("user-role", "MASTER");

        assertUnauthorized(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {AuthHeaders.USER_ID, AuthHeaders.USER_ROLE})
    void rejectsCommaCombinedIdentityValues(String combinedHeader) {
        var request = new MockHttpServletRequest();
        request.addHeader(AuthHeaders.USER_ID,
                AuthHeaders.USER_ID.equals(combinedHeader) ? USER_ID + "," + USER_ID : USER_ID);
        request.addHeader(AuthHeaders.USER_ROLE,
                AuthHeaders.USER_ROLE.equals(combinedHeader) ? "USER,MASTER" : "USER");

        assertUnauthorized(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "550e8400e29b41d4a716446655440000"})
    void rejectsMalformedOrNonCanonicalUuids(String invalidId) {
        assertUnauthorized(request(invalidId, "USER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "user", "ROLE_USER", "ROOT"})
    void rejectsUnsupportedRolesInsteadOfChoosingADefault(String invalidRole) {
        assertUnauthorized(request(USER_ID, invalidRole));
    }

    @Test
    void malformedHeadersProduceTheSafeCommonUnauthorizedResponse() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        var result = mvc.perform(get("/test/identity-headers")
                        .header(AuthHeaders.USER_ID, "DO_NOT_ECHO_HEADER_VALUE")
                        .header(AuthHeaders.USER_ROLE, "USER"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andReturn();

        assertFalse(result.getResponse().getContentAsString(UTF_8).contains("DO_NOT_ECHO"));
    }

    private static MockHttpServletRequest request(String userId, String role) {
        var request = new MockHttpServletRequest();
        request.addHeader(AuthHeaders.USER_ID, userId);
        request.addHeader(AuthHeaders.USER_ROLE, role);
        return request;
    }

    private static void assertUnauthorized(MockHttpServletRequest request) {
        var error = assertThrows(BusinessException.class, () -> CurrentUserHeaderParser.parse(request));
        assertSame(CommonErrorCode.UNAUTHORIZED, error.getErrorCode());
    }

    @RestController
    static class ProbeController {

        @GetMapping("/test/identity-headers")
        void parseHeaders(HttpServletRequest request) {
            CurrentUserHeaderParser.parse(request);
        }
    }
}
