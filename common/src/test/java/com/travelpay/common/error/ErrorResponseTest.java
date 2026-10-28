package com.travelpay.common.error;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ErrorResponseTest {

    @Test
    void aGeneric403DoesNotClaimThatTheFailureWasCausedByCsrf() {
        assertEquals("COMMON_FORBIDDEN", ErrorResponse.fromHttpStatus(403).code());
    }

    @Test
    void validationDetailsRemainStableWhenTheCallerChangesTheOriginalList() {
        var fieldError = new ErrorResponse.FieldError("email", "이메일 형식을 확인해 주세요.");
        var original = new ArrayList<>(List.of(fieldError));
        var response = ErrorResponse.of(CommonErrorCode.INVALID_REQUEST, original);

        original.clear();

        assertEquals(List.of(fieldError), response.errors());
        assertThrows(UnsupportedOperationException.class, () -> response.errors().clear());
    }
}
