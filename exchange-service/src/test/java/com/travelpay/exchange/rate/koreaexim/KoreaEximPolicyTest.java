package com.travelpay.exchange.rate.koreaexim;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.SimpleTriggerContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KoreaEximPolicyTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private final KoreaEximCollectionPolicy collectionPolicy =
            new KoreaEximCollectionPolicy("0 */10 11-13 * * MON-FRI", SEOUL);
    private final KoreaEximFreshnessPolicy freshnessPolicy = new KoreaEximFreshnessPolicy(7, SEOUL);

    @Test
    void collectsUntilTodaysRateIsStored() {
        assertTrue(collectionPolicy.shouldCollect(TODAY, Optional.empty()));
        assertTrue(collectionPolicy.shouldCollect(TODAY, Optional.of(TODAY.minusDays(1))));
        assertFalse(collectionPolicy.shouldCollect(TODAY, Optional.of(TODAY)));
    }

    @Test
    void triggersEveryTenMinutesDuringWeekdayPublishingHours() {
        assertEquals(seoul(2026, 10, 28, 11, 0), next(seoul(2026, 10, 28, 9, 0)));
        assertEquals(seoul(2026, 10, 28, 11, 10), next(seoul(2026, 10, 28, 11, 0)));
        // 수요일 13:50 이후에는 다음 날 11:00
        assertEquals(seoul(2026, 10, 29, 11, 0), next(seoul(2026, 10, 28, 13, 50)));
        // 금요일 고시 시간 이후에는 다음 주 월요일
        assertEquals(seoul(2026, 11, 2, 11, 0), next(seoul(2026, 10, 30, 14, 0)));
    }

    @Test
    void treatsRateAsFreshWithinStaleAfterDaysOfPublishDate() {
        Instant now = seoul(2026, 10, 28, 9, 0);

        assertTrue(freshnessPolicy.isFresh(TODAY, now, now));
        assertTrue(freshnessPolicy.isFresh(TODAY.minusDays(7), now, now));
        assertFalse(freshnessPolicy.isFresh(TODAY.minusDays(8), now, now));
    }

    @Test
    void usesSeoulDateWhenJudgingFreshness() {
        // UTC로는 10월 27일이지만 서울은 10월 28일 아침입니다.
        Instant now = seoul(2026, 10, 28, 8, 0);

        assertFalse(freshnessPolicy.isFresh(LocalDate.of(2026, 10, 20), now, now));
    }

    private Instant next(Instant after) {
        return collectionPolicy.trigger().nextExecution(new SimpleTriggerContext(after, after, after));
    }

    private static Instant seoul(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(LocalDateTime.of(year, month, day, hour, minute), SEOUL).toInstant();
    }
}
