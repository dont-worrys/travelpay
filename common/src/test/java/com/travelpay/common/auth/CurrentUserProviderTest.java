package com.travelpay.common.auth;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CurrentUserProviderTest {

    @Test
    void requiredLookupReturnsTheVerifiedIdentity() {
        var verifiedUser = new AuthenticatedUser(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), AuthRole.USER);
        CurrentUserProvider provider = () -> Optional.of(verifiedUser);

        assertEquals(verifiedUser, provider.requireCurrentUser());
    }

    @Test
    void requiredLookupRejectsAnAbsentIdentityAsUnauthenticated() {
        CurrentUserProvider provider = Optional::empty;

        var error = assertThrows(BusinessException.class, provider::requireCurrentUser);

        assertSame(CommonErrorCode.UNAUTHORIZED, error.getErrorCode());
    }

    @Test
    void requiredLookupPreservesFailuresInsteadOfTreatingThemAsMissingIdentity() {
        var failure = new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        CurrentUserProvider provider = () -> {
            throw failure;
        };

        var error = assertThrows(BusinessException.class, provider::requireCurrentUser);

        assertSame(failure, error);
    }
}
