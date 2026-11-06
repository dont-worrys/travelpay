package com.travelpay.exchange.application.rate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class CacheFailureLogTest {

    private static final String WARNING = "환율 캐시 조회 실패, DB로 처리합니다";
    private static final Instant START = Instant.parse("2026-11-06T02:00:00Z");

    private final MovableClock clock = new MovableClock(START);
    private final CacheFailureLog failureLog = new CacheFailureLog(clock);

    /** Redis가 꺼져 요청마다 실패해도 경고는 1분에 한 번만 남고, 다음 경고에 생략한 건수가 붙습니다. */
    @Test
    void warnsOncePerMinuteAndReportsSuppressedCount(CapturedOutput output) {
        for (int i = 0; i < 100; i++) {
            failureLog.warn("조회", new IllegalStateException("redis down"));
        }
        assertEquals(1, count(output.getOut(), WARNING));

        clock.instant = START.plus(CacheFailureLog.INTERVAL);
        failureLog.warn("조회", new IllegalStateException("redis down"));

        assertEquals(2, count(output.getOut(), WARNING));
        assertTrue(output.getOut().contains("생략한 실패 99건"), "1분 동안 생략한 99건을 함께 남김");
    }

    private static int count(String text, String word) {
        return text.split(word, -1).length - 1;
    }

    private static final class MovableClock extends Clock {

        private Instant instant;

        private MovableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
