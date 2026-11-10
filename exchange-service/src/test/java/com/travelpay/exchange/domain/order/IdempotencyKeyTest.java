package com.travelpay.exchange.domain.order;

import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotencyKeyTest {

    @Test
    void acceptsUuid() {
        String uuid = UUID.randomUUID().toString();

        assertEquals(uuid, new IdempotencyKey(uuid).value());
    }

    @Test
    void acceptsPrintableAsciiUpToHundredChars() {
        String key = "a".repeat(99) + "~";

        assertEquals(key, new IdempotencyKey(key).value());
    }

    @Test
    void rejectsOverHundredChars() {
        assertInvalid("a".repeat(101));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " key", "key ", "ke y", "키", "key\t", "key\n"})
    void rejectsBlankSpaceControlAndNonAscii(String value) {
        assertInvalid(value);
    }

    private static void assertInvalid(String value) {
        BusinessException exception = assertThrows(BusinessException.class, () -> new IdempotencyKey(value));
        assertEquals(ExchangeOrderErrorCode.INVALID_IDEMPOTENCY_KEY, exception.getErrorCode());
    }
}
