package com.travelpay.user.domain.model.entity;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.user.domain.model.vo.TermsDocumentId;
import com.travelpay.user.domain.model.vo.UserConsentId;
import com.travelpay.user.common.error.UserErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserConsentTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-10-30T00:00:00Z");
    private static final Instant AGREED_AT = Instant.parse("2026-10-30T00:01:00Z");

    @Test
    void agreementDerivesItsIdentityFromTheMemberAndTermsAndRecordsTheServerTime() {
        var user = user();
        var terms = terms();

        var consent = UserConsent.agreeTo(user, terms, AGREED_AT);

        assertEquals(new UserConsentId(USER_ID, terms.getId()), consent.getId());
        assertSame(user, consent.getUser());
        assertSame(terms, consent.getTermsDocument());
        assertEquals(AGREED_AT, consent.getAgreedAt());
    }

    @Test
    void agreementIsAllowedAtTheExactEffectiveTime() {
        var consent = UserConsent.agreeTo(user(), terms(), EFFECTIVE_AT);

        assertEquals(EFFECTIVE_AT, consent.getAgreedAt());
    }

    @Test
    void agreementRejectsTermsThatHaveNotTakenEffect() {
        var exception = assertThrows(BusinessException.class,
                () -> UserConsent.agreeTo(user(), terms(), EFFECTIVE_AT.minusSeconds(1)));

        assertEquals(UserErrorCode.TERMS_CONSENT_INVALID, exception.getErrorCode());
    }

    @Test
    void agreementRequiresTheMember() {
        assertThrows(IllegalArgumentException.class, () -> UserConsent.agreeTo(null, terms(), AGREED_AT));
    }

    @Test
    void agreementRequiresTheMemberIdentity() {
        var userWithoutId = User.register("홍길동", "010-1234-5678", EFFECTIVE_AT);

        assertThrows(IllegalArgumentException.class,
                () -> UserConsent.agreeTo(userWithoutId, terms(), AGREED_AT));
    }

    @Test
    void agreementRequiresTheTermsDocument() {
        assertThrows(IllegalArgumentException.class, () -> UserConsent.agreeTo(user(), null, AGREED_AT));
    }

    @Test
    void agreementRequiresTheServerAgreementTime() {
        assertThrows(IllegalArgumentException.class, () -> UserConsent.agreeTo(user(), terms(), null));
    }

    private static User user() {
        var user = User.register("홍길동", "010-1234-5678", EFFECTIVE_AT);
        // 단위 테스트에서만 JPA가 식별자를 부여한 회원 상태를 준비합니다.
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    private static TermsDocument terms() {
        return TermsDocument.publish(new TermsDocumentId("SERVICE_TERMS", "v1"),
                "서비스 이용약관", "약관 원문", true, EFFECTIVE_AT);
    }
}
