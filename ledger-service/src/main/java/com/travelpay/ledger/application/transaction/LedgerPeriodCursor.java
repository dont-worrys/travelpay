package com.travelpay.ledger.application.transaction;

import com.travelpay.common.error.BusinessException;
import com.travelpay.ledger.domain.LedgerErrorCode;
import com.travelpay.ledger.domain.transaction.LedgerTransactionPosition;
import com.travelpay.ledger.domain.transaction.ReferenceType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * 기간 목록의 다음 페이지 커서. 조회 조건(referenceType, from, to)과 마지막으로 돌려준 행의 위치(createdAt, id)를 담는다.
 * 시각은 epoch 마이크로초 정수로 담아 만들고 되돌리는 과정에서 정밀도·시각이 바뀌지 않게 한다 (DB 정밀도 = 마이크로초).
 * 형식: Base64URL("v1:{referenceType}:{fromMicros}:{toMicros}:{createdAtMicros}:{id}"). 호출자에게는 불투명 문자열.
 */
record LedgerPeriodCursor(ReferenceType referenceType, Instant from, Instant to, LedgerTransactionPosition position) {

    private static final String VERSION = "v1";
    private static final String SEPARATOR = ":";
    private static final int PARTS = 6;

    LedgerPeriodCursor {
        Objects.requireNonNull(referenceType, "referenceType");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(position, "position");
    }

    /** 조건 비교는 문자열이 아니라 시각 값으로 한다 (Z와 +09:00 같은 표기 차이와 무관) */
    boolean matches(ReferenceType referenceType, Instant from, Instant to) {
        return this.referenceType == referenceType && this.from.equals(from) && this.to.equals(to);
    }

    String encode() {
        String raw = String.join(SEPARATOR, VERSION, referenceType.name(),
                Long.toString(toMicros(from)), Long.toString(toMicros(to)),
                Long.toString(toMicros(position.createdAt())), position.id().toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static LedgerPeriodCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(SEPARATOR, -1);
            if (parts.length != PARTS || !VERSION.equals(parts[0])) {
                throw invalid();
            }
            return new LedgerPeriodCursor(ReferenceType.valueOf(parts[1]),
                    fromMicros(Long.parseLong(parts[2])), fromMicros(Long.parseLong(parts[3])),
                    new LedgerTransactionPosition(fromMicros(Long.parseLong(parts[4])), UUID.fromString(parts[5])));
        } catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException e) {
            throw invalid();
        }
    }

    /** 마이크로초 미만이 있으면 다음 마이크로초로 올린다. 정확히 마이크로초면 그대로 */
    static Instant ceilToMicros(Instant instant) {
        Instant truncated = instant.truncatedTo(ChronoUnit.MICROS);
        return truncated.equals(instant) ? instant : truncated.plus(1, ChronoUnit.MICROS);
    }

    private static long toMicros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
    }

    private static Instant fromMicros(long micros) {
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
    }

    private static BusinessException invalid() {
        return new BusinessException(LedgerErrorCode.LEDGER_INVALID_CURSOR);
    }
}
