package com.travelpay.user.domain.model.entity;

import java.time.Instant;
import java.util.stream.Stream;

import com.travelpay.user.domain.model.vo.TermsDocumentId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TermsDocumentTest {

    private static final Instant EFFECTIVE_AT = Instant.parse("2026-10-30T00:00:00Z");

    @Test
    void publishingPreservesTheOriginalContentAndRecordsTheVersionMetadata() {
        var id = id();
        var content = "\n제1조 서비스 이용 목적\n\n제2조 회원의 권리와 의무\n";

        var document = TermsDocument.publish(id, " 서비스 이용약관 ", content, true, EFFECTIVE_AT);

        assertEquals(id, document.getId());
        assertEquals("서비스 이용약관", document.getTitle());
        assertEquals(content, document.getContent());
        assertTrue(document.isRequired());
        assertEquals(EFFECTIVE_AT, document.getEffectiveAt());
    }

    @Test
    void publishingAllowsOptionalTermsAndLongOriginalContent() {
        var title = "가".repeat(200);
        var content = "약관 원문\n".repeat(1000);

        var document = TermsDocument.publish(id(), title, content, false, EFFECTIVE_AT);

        assertEquals(title, document.getTitle());
        assertEquals(content, document.getContent());
        assertFalse(document.isRequired());
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-29T23:59:59Z,false",
            "2026-10-30T00:00:00Z,true",
            "2026-10-30T00:00:01Z,true"
    })
    void aVersionHasTakenEffectStartingAtItsEffectiveTime(String time, boolean expected) {
        var document = TermsDocument.publish(id(), "서비스 이용약관", "약관 원문", true, EFFECTIVE_AT);

        assertEquals(expected, document.hasTakenEffect(Instant.parse(time)));
    }

    @ParameterizedTest
    @MethodSource("invalidTitles")
    void publishingRejectsMissingOrOverlongTitles(String title) {
        assertThrows(IllegalArgumentException.class,
                () -> TermsDocument.publish(id(), title, "약관 원문", true, EFFECTIVE_AT));
    }

    @ParameterizedTest
    @MethodSource("invalidContents")
    void publishingRejectsMissingOrUnstorableContent(String content) {
        assertThrows(IllegalArgumentException.class,
                () -> TermsDocument.publish(id(), "서비스 이용약관", content, true, EFFECTIVE_AT));
    }

    @Test
    void publishingRequiresTheVersionIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> TermsDocument.publish(null, "서비스 이용약관", "약관 원문", true, EFFECTIVE_AT));
    }

    @Test
    void publishingRequiresTheEffectiveTime() {
        assertThrows(IllegalArgumentException.class,
                () -> TermsDocument.publish(id(), "서비스 이용약관", "약관 원문", true, null));
    }

    @Test
    void checkingWhetherAVersionHasTakenEffectRequiresTheComparisonTime() {
        var document = TermsDocument.publish(id(), "서비스 이용약관", "약관 원문", true, EFFECTIVE_AT);

        assertThrows(IllegalArgumentException.class, () -> document.hasTakenEffect(null));
    }

    private static TermsDocumentId id() {
        return new TermsDocumentId("SERVICE_TERMS", "2026-10-01");
    }

    private static Stream<String> invalidTitles() {
        return Stream.of(null, "", " ", "가".repeat(201), "제목\n다음 줄");
    }

    private static Stream<String> invalidContents() {
        return Stream.of(null, "", " \n\t", "본문\u0000내용");
    }
}
