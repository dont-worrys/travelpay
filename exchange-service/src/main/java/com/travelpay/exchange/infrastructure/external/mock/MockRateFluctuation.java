package com.travelpay.exchange.infrastructure.external.mock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * 가짜 환율의 다음 값을 만듭니다. 직전 값에서 조금 오르내리되, 기준값(중심)에서 너무 멀어지지 않게 합니다.
 * - 한 번에 직전 값의 ±maxStepRate 이내로 움직입니다. (예: 0.0005 = ±0.05%)
 * - 기준값의 ±maxDeviationRate를 넘지 않습니다. (예: 0.02 = ±2%) 넘으면 경계값으로 맞춥니다.
 * - 결과는 소수 둘째 자리입니다. 경계도 안쪽으로 반올림해, 반올림 때문에 경계를 넘지 않습니다.
 * 난수는 밖에서 받습니다. 테스트에서 정해진 값을 넣으면 결과가 항상 같습니다.
 */
final class MockRateFluctuation {

    static final int SCALE = 2;

    private final BigDecimal maxStepRate;
    private final BigDecimal maxDeviationRate;
    private final RandomGenerator random;

    /**
     * @param maxStepRate      한 번에 움직이는 최대 비율. 0보다 크고 maxDeviationRate 이하
     * @param maxDeviationRate 기준값에서 벗어날 수 있는 최대 비율. 0보다 크고 1보다 작음 (1 이상이면 하한이 0 이하가 됨)
     */
    MockRateFluctuation(BigDecimal maxStepRate, BigDecimal maxDeviationRate, RandomGenerator random) {
        requireRatio(maxDeviationRate, "maxDeviationRate");
        requireRatio(maxStepRate, "maxStepRate");
        if (maxStepRate.compareTo(maxDeviationRate) > 0) {
            throw new IllegalArgumentException("maxStepRate must not exceed maxDeviationRate");
        }
        this.maxStepRate = maxStepRate;
        this.maxDeviationRate = maxDeviationRate;
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * 다음 환율을 만듭니다.
     * 직전 값이 범위 밖이면(기준값이 바뀐 경우) 범위 안의 가장 가까운 값으로 맞춥니다.
     *
     * @param base     기준값(중심). 0보다 커야 합니다
     * @param previous 직전 값. 0보다 커야 합니다
     */
    BigDecimal next(BigDecimal base, BigDecimal previous) {
        requirePositive(base, "base");
        requirePositive(previous, "previous");
        // -1.0 이상 1.0 미만의 난수만큼 최대 폭을 곱해 움직인다. 0이면 그대로, 1에 가까우면 최대로 오른다
        BigDecimal direction = BigDecimal.valueOf(random.nextDouble(-1.0, 1.0));
        BigDecimal step = previous.multiply(maxStepRate).multiply(direction);
        BigDecimal candidate = previous.add(step).setScale(SCALE, RoundingMode.HALF_UP);
        return clamp(candidate, lowerBound(base), upperBound(base));
    }

    /** 기준값 × (1 - 최대 비율)을 올림. 반올림으로 하한 아래로 내려가지 않게 한다 */
    BigDecimal lowerBound(BigDecimal base) {
        return base.multiply(BigDecimal.ONE.subtract(maxDeviationRate)).setScale(SCALE, RoundingMode.CEILING);
    }

    /** 기준값 × (1 + 최대 비율)을 내림. 반올림으로 상한 위로 올라가지 않게 한다 */
    BigDecimal upperBound(BigDecimal base) {
        return base.multiply(BigDecimal.ONE.add(maxDeviationRate)).setScale(SCALE, RoundingMode.FLOOR);
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal lower, BigDecimal upper) {
        return value.max(lower).min(upper);
    }

    private static void requireRatio(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0 || value.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1 (exclusive)");
        }
    }

    private static void requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
