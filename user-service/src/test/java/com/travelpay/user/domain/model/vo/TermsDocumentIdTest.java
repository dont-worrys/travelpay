package com.travelpay.user.domain.model.vo;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TermsDocumentIdTest {

    @Test
    void identityUsesTheCodeAndVersionAfterStrippingOuterWhitespace() {
        var id = new TermsDocumentId(" SERVICE_TERMS ", " 2026-10-01 ");

        assertEquals(new TermsDocumentId("SERVICE_TERMS", "2026-10-01"), id);
        assertNotEquals(new TermsDocumentId("PRIVACY_TERMS", "2026-10-01"), id);
        assertNotEquals(new TermsDocumentId("SERVICE_TERMS", "2026-11-01"), id);
    }

    @Test
    void identityAcceptsBothPartsAtTheirColumnLengthLimits() {
        var id = new TermsDocumentId("A".repeat(40), "1".repeat(40));

        assertEquals("A".repeat(40), id.termsCode());
        assertEquals("1".repeat(40), id.termsVersion());
    }

    @ParameterizedTest
    @MethodSource("invalidParts")
    void identityRejectsInvalidCodes(String code) {
        assertThrows(IllegalArgumentException.class, () -> new TermsDocumentId(code, "2026-10-01"));
    }

    @ParameterizedTest
    @MethodSource("invalidParts")
    void identityRejectsInvalidVersions(String version) {
        assertThrows(IllegalArgumentException.class, () -> new TermsDocumentId("SERVICE_TERMS", version));
    }

    private static Stream<String> invalidParts() {
        return Stream.of(null, "", " ", "A".repeat(41), "A\nB", "A\u0000B");
    }
}
