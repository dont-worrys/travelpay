package com.travelpay.user.application.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.travelpay.user.domain.model.vo.TermsDocumentId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SignupCommandTest {

    private static final String EMAIL = "member@example.com";
    private static final String PASSWORD = "TravelpayDemo-2026!";
    private static final String REAL_NAME = "홍길동";
    private static final String PHONE = "010-1234-5678";
    private static final TermsDocumentId SERVICE_TERMS = new TermsDocumentId("SERVICE_TERMS", "v1");
    private static final TermsDocumentId PRIVACY_TERMS = new TermsDocumentId("PRIVACY_TERMS", "v1");

    @Test
    void keepsTheSubmittedConsentSnapshotIncludingDuplicatesForLaterValidation() {
        var submitted = new ArrayList<>(List.of(SERVICE_TERMS, PRIVACY_TERMS, SERVICE_TERMS));
        var command = command(submitted);

        submitted.clear();

        assertEquals(List.of(SERVICE_TERMS, PRIVACY_TERMS, SERVICE_TERMS), command.consents());
    }

    @Test
    void preventsTheConsentSnapshotFromBeingChangedThroughTheAccessor() {
        var command = command(new ArrayList<>(List.of(SERVICE_TERMS)));

        assertThrows(UnsupportedOperationException.class, () -> command.consents().add(PRIVACY_TERMS));
    }

    @Test
    void stringRepresentationDoesNotExposeThePasswordOrPersonalInput() {
        var command = command(List.of());
        var text = command.toString();

        for (var sensitiveValue : List.of(EMAIL, PASSWORD, REAL_NAME, PHONE)) {
            assertFalse(text.contains(sensitiveValue));
        }
    }

    @Test
    void rejectsAMissingConsentList() {
        assertThrows(NullPointerException.class, () -> command(null));
    }

    @Test
    void rejectsAMissingConsentInsideTheList() {
        assertThrows(NullPointerException.class, () -> command(Collections.singletonList(null)));
    }

    private static SignupCommand command(List<TermsDocumentId> consents) {
        return new SignupCommand(EMAIL, PASSWORD, REAL_NAME, PHONE, consents);
    }
}
