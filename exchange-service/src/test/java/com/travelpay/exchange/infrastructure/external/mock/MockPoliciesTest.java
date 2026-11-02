package com.travelpay.exchange.infrastructure.external.mock;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.PeriodicTrigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockPoliciesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    private static final Instant NOW = Instant.parse("2026-11-02T02:00:00Z");

    @Test
    void collectsEveryIntervalWithoutDailyLimit() {
        MockCollectionPolicy policy = new MockCollectionPolicy(Duration.ofSeconds(5));

        PeriodicTrigger trigger = assertInstanceOf(PeriodicTrigger.class, policy.trigger());
        assertEquals(Duration.ofSeconds(5), trigger.getPeriodDuration());
        assertFalse(trigger.isFixedRate());
        // 오늘 이미 저장했어도 계속 수집한다
        assertTrue(policy.shouldCollect(TODAY, TODAY));
        assertFalse(policy.publishesOncePerDay());
    }

    /** 수집 시각이 30초 이내면 최신. 고시일은 보지 않는다 */
    @Test
    void freshnessIsJudgedByCollectedAt() {
        MockFreshnessPolicy policy = new MockFreshnessPolicy(Duration.ofSeconds(30));

        assertTrue(policy.isFresh(TODAY, NOW.minusSeconds(5), NOW));
        assertTrue(policy.isFresh(TODAY, NOW.minusSeconds(30), NOW));
        assertFalse(policy.isFresh(TODAY, NOW.minusSeconds(30).minusMillis(1), NOW));
        // 고시일이 어제여도 수집 시각이 최근이면 최신
        assertTrue(policy.isFresh(TODAY.minusDays(1), NOW.minusSeconds(5), NOW));
    }

    @Test
    void rejectsNonPositiveDurations() {
        assertThrows(IllegalArgumentException.class, () -> new MockCollectionPolicy(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new MockFreshnessPolicy(Duration.ofSeconds(-1)));
    }
}
