package com.travelpay.wallet.application.reconciliation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DurationFormat;
import org.springframework.format.datetime.standard.DurationFormatterUtils;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 충전 대사 기간·실행 설정. 스케줄러를 꺼도 수동 실행이 쓰므로 스케줄러와 분리한다.
 * 값이 잘못되면 IllegalStateException으로 기동을 멈춘다.
 */
@Component
public class ReconciliationSettings {

    static final String PREFIX = "travelpay.wallet.charge.reconciliation.";

    private final Duration grace;
    private final Duration overlap;
    private final Duration maxPeriod;
    private final Duration runningTimeout;
    private final Instant initialFrom;

    public ReconciliationSettings(@Value("${" + PREFIX + "grace:5m}") String grace,
                                  @Value("${" + PREFIX + "overlap:10m}") String overlap,
                                  @Value("${" + PREFIX + "max-period:1d}") String maxPeriod,
                                  @Value("${" + PREFIX + "running-timeout:30m}") String runningTimeout,
                                  @Value("${" + PREFIX + "initial-from:}") String initialFrom) {
        this.grace = positive("grace", grace);
        this.overlap = duration("overlap", overlap);
        if (this.overlap.isNegative()) {
            throw new IllegalStateException(PREFIX + "overlap은 0 이상이어야 합니다: " + overlap);
        }
        this.maxPeriod = positive("max-period", maxPeriod);
        this.runningTimeout = positive("running-timeout", runningTimeout);
        this.initialFrom = instant(initialFrom);
    }

    /** 여유 시간 */
    public Duration grace() {
        return grace;
    }

    /** 직전 기간과 겹쳐 다시 보는 시간 */
    public Duration overlap() {
        return overlap;
    }

    /** 실행 한 번의 최대 기간 */
    public Duration maxPeriod() {
        return maxPeriod;
    }

    /** 이보다 오래된 RUNNING은 FAILED로 정리 */
    public Duration runningTimeout() {
        return runningTimeout;
    }

    /** 직전 실행이 없을 때 시작점 */
    public Instant initialFrom() {
        return initialFrom;
    }

    /** 닫힌 기간의 끝: 지금 − 여유 시간을 마이크로초로 내림 (DB timestamptz 정밀도) */
    public Instant closedUntil(Instant now) {
        return now.minus(grace).truncatedTo(ChronoUnit.MICROS);
    }

    private static Duration positive(String key, String value) {
        Duration parsed = duration(key, value);
        if (parsed.isNegative() || parsed.isZero()) {
            throw new IllegalStateException(PREFIX + key + "는 0보다 커야 합니다: " + value);
        }
        return parsed;
    }

    /** "5m"·"1d"·ISO-8601을 받는다. 단위 없는 숫자는 밀리초 */
    private static Duration duration(String key, String value) {
        try {
            return DurationFormatterUtils.detectAndParse(value, DurationFormat.Unit.MILLIS);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(PREFIX + key + " 형식이 잘못되었습니다: " + value, e);
        }
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(PREFIX + "initial-from이 필요합니다 (예: 2026-11-01T00:00:00Z)");
        }
        try {
            return Instant.parse(value.strip()).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeException e) {
            throw new IllegalStateException(PREFIX + "initial-from 형식이 잘못되었습니다: " + value, e);
        }
    }
}
