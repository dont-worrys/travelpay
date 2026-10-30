package com.travelpay.user.domain.model.entity;

import java.time.Instant;
import java.util.stream.Stream;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.user.domain.model.vo.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserTest {

    private static final Instant PHONE_CHECKED_AT = Instant.parse("2026-10-29T00:00:00Z");

    @Test
    void registrationCreatesAnActiveRegularMember() {
        var user = User.register("홍길동", "010-1234-5678", PHONE_CHECKED_AT);

        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertEquals(AuthRole.USER, user.getRole());
    }

    @Test
    void registrationRecordsThePhoneCheckTimeAndDefersAuditDatesToPersistence() {
        var user = User.register("홍길동", "010-1234-5678", PHONE_CHECKED_AT);

        assertEquals(PHONE_CHECKED_AT, user.getPhoneVerifiedAt());
        assertNull(user.getCreatedAt());
        assertNull(user.getUpdatedAt());
    }

    @Test
    void registrationStripsOuterWhitespaceFromTheRealName() {
        var user = User.register("  홍길동  ", "010-1234-5678", PHONE_CHECKED_AT);

        assertEquals("홍길동", user.getRealName());
    }

    @Test
    void registrationAcceptsARealNameAtTheColumnLengthLimit() {
        var realName = "가".repeat(40);

        var user = User.register(realName, "010-1234-5678", PHONE_CHECKED_AT);

        assertEquals(realName, user.getRealName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"010-1234-5678", "010-0000-0000", "010-9999-9999"})
    void registrationPreservesTheDomesticPhoneNumber(String phone) {
        var user = User.register("홍길동", phone, PHONE_CHECKED_AT);

        assertEquals(phone, user.getPhone());
    }

    @ParameterizedTest
    @MethodSource("invalidRealNames")
    void registrationRejectsMissingOrOverlongRealNames(String realName) {
        assertThrows(IllegalArgumentException.class,
                () -> User.register(realName, "010-1234-5678", PHONE_CHECKED_AT));
    }

    @ParameterizedTest
    @MethodSource("invalidDomesticPhones")
    void registrationRejectsPhoneNumbersOutsideTheDomesticFormat(String phone) {
        assertThrows(IllegalArgumentException.class,
                () -> User.register("홍길동", phone, PHONE_CHECKED_AT));
    }

    @Test
    void registrationRequiresThePhoneFormatCheckTime() {
        assertThrows(IllegalArgumentException.class,
                () -> User.register("홍길동", "010-1234-5678", null));
    }

    private static Stream<String> invalidRealNames() {
        return Stream.of(null, "", " ", "\t", "가".repeat(41));
    }

    private static Stream<String> invalidDomesticPhones() {
        return Stream.of(null, "", " ", "01012345678", "+821012345678", "+12025550123", "011-1234-5678",
                "010-123-5678", "010-12345-5678", "010-1234-567", "010-1234-56789", "010-12a4-5678",
                "010 1234 5678", " 010-1234-5678", "010-1234-5678 ", "010-1234-5678\n");
    }
}
