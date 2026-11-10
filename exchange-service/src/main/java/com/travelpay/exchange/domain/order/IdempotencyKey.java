package com.travelpay.exchange.domain.order;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.travelpay.common.error.BusinessException;

/**
 * 환전 신청 요청의 멱등 키입니다. 같은 회원이 같은 키로 다시 보내면 같은 신청으로 봅니다.
 * 공백·제어 문자를 뺀 ASCII 1~100자이며 exchange_orders.idempotency_key 길이와 같습니다.
 * UUID도 이 형식에 들어갑니다.
 * "reservation:"으로 시작하는 키는 예약 환전 체결 주문이 쓰므로, 회원의 환전 신청에서는 받지 않습니다.
 */
public record IdempotencyKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[\\x21-\\x7E]{1,100}$");

    /** 예약 환전 체결 주문의 키 앞부분 */
    static final String RESERVATION_PREFIX = "reservation:";

    /** 형식이 아니면 EXCHANGE_INVALID_IDEMPOTENCY_KEY(400) 오류입니다. 앞뒤 공백도 허용하지 않습니다. */
    public IdempotencyKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new BusinessException(ExchangeOrderErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
    }

    /** 예약 환전 체결로 만드는 주문의 키입니다. 예약 하나에 키 하나라 같은 예약으로 주문을 두 번 만들 수 없습니다. */
    public static IdempotencyKey forReservation(UUID reservationId) {
        return new IdempotencyKey(RESERVATION_PREFIX + reservationId);
    }

    /** 예약 환전 체결 주문용 키인지 확인합니다. 회원의 환전 신청이 이 키를 쓰면 체결 주문과 겹칠 수 있습니다. */
    public boolean isReservedForReservation() {
        return value.startsWith(RESERVATION_PREFIX);
    }

    /**
     * 예약 환전 체결 주문의 키면 그 예약 ID입니다(#254). 회원이 직접 신청한 주문의 키면 empty입니다.
     * 접두어 뒤가 UUID가 아니면 체결 주문 키가 아니므로 empty입니다(예외로 응답을 깨뜨리지 않음).
     */
    public Optional<UUID> reservationId() {
        if (!isReservedForReservation()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.substring(RESERVATION_PREFIX.length())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
