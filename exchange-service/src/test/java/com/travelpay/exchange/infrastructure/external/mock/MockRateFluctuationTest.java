package com.travelpay.exchange.infrastructure.external.mock;

import java.math.BigDecimal;
import java.util.Random;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockRateFluctuationTest {

    private static final BigDecimal STEP = new BigDecimal("0.0005");
    private static final BigDecimal DEVIATION = new BigDecimal("0.02");
    private static final BigDecimal BASE = new BigDecimal("838.30");

    /** 직전 값에서 난수만큼 움직인다. 838.30의 0.05%는 0.41915 */
    @ParameterizedTest
    @CsvSource({
            "1.0,  838.72",   // 최대로 오름: 838.30 + 0.41915 → 838.72
            "-1.0, 837.88",   // 최대로 내림: 838.30 - 0.41915 → 837.88
            "0.0,  838.30",   // 그대로
            "0.5,  838.51"    // 절반만 오름: 838.30 + 0.209575 → 838.51
    })
    void movesFromPreviousByRandomShareOfMaxStep(double direction, String expected) {
        MockRateFluctuation fluctuation = new MockRateFluctuation(STEP, DEVIATION, fixed(direction));

        assertEquals(new BigDecimal(expected), fluctuation.next(BASE, BASE));
    }

    /** 기준값 838.30의 ±2%는 821.534 ~ 855.066. 경계는 안쪽으로 반올림해 821.54 ~ 855.06 */
    @Test
    void boundsAreRoundedInward() {
        MockRateFluctuation fluctuation = new MockRateFluctuation(STEP, DEVIATION, fixed(0));

        assertEquals(new BigDecimal("821.54"), fluctuation.lowerBound(BASE));
        assertEquals(new BigDecimal("855.06"), fluctuation.upperBound(BASE));
    }

    @Test
    void staysAtUpperBoundWhenStepWouldCrossIt() {
        MockRateFluctuation fluctuation = new MockRateFluctuation(STEP, DEVIATION, fixed(1.0));

        assertEquals(new BigDecimal("855.06"), fluctuation.next(BASE, new BigDecimal("855.06")));
    }

    @Test
    void staysAtLowerBoundWhenStepWouldCrossIt() {
        MockRateFluctuation fluctuation = new MockRateFluctuation(STEP, DEVIATION, fixed(-1.0));

        assertEquals(new BigDecimal("821.54"), fluctuation.next(BASE, new BigDecimal("821.54")));
    }

    /** 기준값이 새 공식 환율로 바뀌어 직전 값이 범위 밖이면, 새 범위의 가장 가까운 경계로 맞춘다 */
    @Test
    void previousOutsideNewRangeIsPulledToNearestBound() {
        MockRateFluctuation fluctuation = new MockRateFluctuation(STEP, DEVIATION, fixed(0));
        BigDecimal newBase = new BigDecimal("900.00");   // 범위 882.00 ~ 918.00

        assertEquals(new BigDecimal("882.00"), fluctuation.next(newBase, BASE));
    }

    /** 실제 난수로 1만 번 움직여도 한 번 변동 폭과 상·하한을 넘지 않는다 */
    @Test
    void manyRandomStepsStayWithinStepAndRange() {
        MockRateFluctuation fluctuation = new MockRateFluctuation(STEP, DEVIATION, new Random(42));
        BigDecimal lower = fluctuation.lowerBound(BASE);
        BigDecimal upper = fluctuation.upperBound(BASE);
        // 한 번 변동 최대 폭 + 반올림 0.01
        BigDecimal maxMove = upper.multiply(STEP).add(new BigDecimal("0.01"));

        BigDecimal previous = BASE;
        for (int i = 0; i < 10_000; i++) {
            BigDecimal next = fluctuation.next(BASE, previous);
            assertTrue(next.compareTo(lower) >= 0 && next.compareTo(upper) <= 0, "범위 밖: " + next);
            assertTrue(next.subtract(previous).abs().compareTo(maxMove) <= 0, previous + " → " + next);
            assertEquals(MockRateFluctuation.SCALE, next.scale());
            previous = next;
        }
    }

    @Test
    void sameRandomSeedGivesSameSequence() {
        MockRateFluctuation first = new MockRateFluctuation(STEP, DEVIATION, new Random(7));
        MockRateFluctuation second = new MockRateFluctuation(STEP, DEVIATION, new Random(7));

        BigDecimal a = BASE;
        BigDecimal b = BASE;
        for (int i = 0; i < 100; i++) {
            a = first.next(BASE, a);
            b = second.next(BASE, b);
        }
        assertEquals(a, b);
    }

    @Test
    void rejectsInvalidSettings() {
        RandomGenerator random = fixed(0);
        assertThrows(IllegalArgumentException.class,
                () -> new MockRateFluctuation(BigDecimal.ZERO, DEVIATION, random));
        assertThrows(IllegalArgumentException.class,
                () -> new MockRateFluctuation(STEP, BigDecimal.ONE, random));
        // 한 번 변동 폭이 전체 범위보다 크면 안 된다
        assertThrows(IllegalArgumentException.class,
                () -> new MockRateFluctuation(new BigDecimal("0.03"), DEVIATION, random));
    }

    @Test
    void rejectsNonPositiveRates() {
        MockRateFluctuation fluctuation = new MockRateFluctuation(STEP, DEVIATION, fixed(0));

        assertThrows(IllegalArgumentException.class, () -> fluctuation.next(BigDecimal.ZERO, BASE));
        assertThrows(IllegalArgumentException.class, () -> fluctuation.next(BASE, new BigDecimal("-1")));
    }

    /** nextDouble(-1, 1)이 항상 같은 값을 돌려주는 난수. 방향을 정해 놓고 결과를 확인한다 */
    private static RandomGenerator fixed(double direction) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public double nextDouble(double origin, double bound) {
                return direction;
            }
        };
    }
}
