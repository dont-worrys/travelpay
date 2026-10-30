package com.travelpay.user.domain.model.vo;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserConsentIdTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void identityIncludesTheMemberAndBothPartsOfTheTermsIdentity() {
        var id = new UserConsentId(USER_ID, new TermsDocumentId("SERVICE_TERMS", "v1"));

        assertEquals(new UserConsentId(USER_ID, new TermsDocumentId("SERVICE_TERMS", "v1")), id);
        assertNotEquals(new UserConsentId(UUID.randomUUID(), new TermsDocumentId("SERVICE_TERMS", "v1")), id);
        assertNotEquals(new UserConsentId(USER_ID, new TermsDocumentId("PRIVACY_TERMS", "v1")), id);
        assertNotEquals(new UserConsentId(USER_ID, new TermsDocumentId("SERVICE_TERMS", "v2")), id);
    }

    @Test
    void identityRequiresTheMemberId() {
        assertThrows(IllegalArgumentException.class,
                () -> new UserConsentId(null, new TermsDocumentId("SERVICE_TERMS", "v1")));
    }

    @Test
    void identityRequiresTheTermsIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new UserConsentId(USER_ID, null));
    }
}
