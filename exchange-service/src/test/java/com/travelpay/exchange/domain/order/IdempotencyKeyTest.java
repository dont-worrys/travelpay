package com.travelpay.exchange.domain.order;

import java.util.Optional;
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

    /** 예약 체결 주문의 키에서만 예약 ID가 나온다(#254). 회원이 직접 신청한 키는 empty */
    @Test
    void reservationIdComesOnlyFromReservationOrderKey() {
        UUID reservationId = UUID.randomUUID();

        assertEquals(Optional.of(reservationId), IdempotencyKey.forReservation(reservationId).reservationId());
        assertEquals(Optional.empty(), new IdempotencyKey(UUID.randomUUID().toString()).reservationId());
        assertEquals(Optional.empty(), new IdempotencyKey("key-1").reservationId());
    }

    /** 접두어 뒤가 UUID가 아니면 체결 주문 키가 아니다. 예외로 주문 응답을 깨뜨리지 않고 empty */
    @ParameterizedTest
    @ValueSource(strings = {"reservation:", "reservation:abc", "reservation:12345"})
    void reservationPrefixWithoutUuidIsNotReservationOrder(String value) {
        assertEquals(Optional.empty(), new IdempotencyKey(value).reservationId());
    }

    private static void assertInvalid(String value) {
        BusinessException exception = assertThrows(BusinessException.class, () -> new IdempotencyKey(value));
        assertEquals(ExchangeOrderErrorCode.INVALID_IDEMPOTENCY_KEY, exception.getErrorCode());
    }
}
