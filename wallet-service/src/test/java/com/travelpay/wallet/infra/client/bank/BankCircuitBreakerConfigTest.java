package com.travelpay.wallet.infra.client.bank;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** travelpay.client.bank.circuit-breaker.* 바인딩·잘못된 값 기동 실패·지표 */
class BankCircuitBreakerConfigTest {

    private static final String PREFIX = BankCircuitBreakerProperties.PREFIX + ".";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BankCircuitBreakerConfig.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void 설정_파일의_값이_서킷_설정으로_옮겨진다(String file) {
        runner.withPropertyValues(circuitBreakerProperties(file)).run(context -> {
            assertTrue(context.getStartupFailure() == null, () -> String.valueOf(context.getStartupFailure()));
            CircuitBreakerConfig config = context.getBean(CircuitBreakerRegistry.class).getDefaultConfig();
            assertEquals(SlidingWindowType.COUNT_BASED, config.getSlidingWindowType());
            assertEquals(5, config.getSlidingWindowSize());
            assertEquals(5, config.getMinimumNumberOfCalls());
            assertEquals(100f, config.getFailureRateThreshold());
            // 고정 대기(지수 증가 없음): 몇 번째로 열리든 10초
            assertEquals(Duration.ofSeconds(10).toMillis(), config.getWaitIntervalFunctionInOpenState().apply(1));
            assertEquals(Duration.ofSeconds(10).toMillis(), config.getWaitIntervalFunctionInOpenState().apply(5));
            assertTrue(config.isAutomaticTransitionFromOpenToHalfOpenEnabled());
            assertEquals(1, config.getPermittedNumberOfCallsInHalfOpenState());
            assertEquals(BankCircuitBreakerConfig.SLOW_CALL_DISABLED, config.getSlowCallDurationThreshold());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "sliding-window-size=0",
            "minimum-number-of-calls=0",
            "minimum-number-of-calls=6",
            "failure-rate-threshold=0",
            "failure-rate-threshold=101",
            "wait-duration-in-open-state=0s",
            "wait-duration-in-open-state=-1s",
            "permitted-number-of-calls-in-half-open-state=0"})
    void 잘못된_값이면_기동에_실패한다(String override) {
        runner.withPropertyValues(validWith(PREFIX + override)).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertInstanceOf(IllegalStateException.class, rootCause(context.getStartupFailure()));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"sliding-window-size", "minimum-number-of-calls", "failure-rate-threshold",
            "wait-duration-in-open-state", "permitted-number-of-calls-in-half-open-state"})
    void 값이_없으면_기동에_실패한다(String missing) {
        List<String> values = new ArrayList<>(List.of(valid()));
        values.removeIf(value -> value.startsWith(PREFIX + missing + "="));
        runner.withPropertyValues(values.toArray(String[]::new)).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertInstanceOf(IllegalStateException.class, rootCause(context.getStartupFailure()));
        });
    }

    @Test
    void 은행별_서킷_상태_지표와_열린_횟수가_등록된다() {
        runner.withPropertyValues(valid()).run(context -> {
            MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
            CircuitBreaker circuitBreaker = context.getBean(CircuitBreakerRegistry.class).circuitBreaker("004");

            circuitBreaker.transitionToOpenState();
            circuitBreaker.transitionToHalfOpenState();
            circuitBreaker.transitionToOpenState();

            assertEquals(1.0, meterRegistry.get("resilience4j.circuitbreaker.state")
                    .tag("name", "004").tag("state", "open").gauge().value());
            assertEquals(2.0, meterRegistry.get(BankCircuitBreakerConfig.OPENED_METRIC)
                    .tag("bank", "004").counter().count());
        });
    }

    private static String[] circuitBreakerProperties(String file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(file));
        Properties properties = yaml.getObject();
        List<String> values = new ArrayList<>();
        // 숫자 값은 String이 아니라 stringPropertyNames()에 나오지 않으므로 키를 직접 본다
        properties.forEach((name, value) -> {
            if (name.toString().startsWith(PREFIX)) {
                values.add(name + "=" + value);
            }
        });
        assertEquals(5, values.size(), file + "에 서킷 설정 5개가 있어야 한다: " + values);
        return values.toArray(String[]::new);
    }

    private static String[] valid() {
        return new String[]{
                PREFIX + "sliding-window-size=5",
                PREFIX + "minimum-number-of-calls=5",
                PREFIX + "failure-rate-threshold=100",
                PREFIX + "wait-duration-in-open-state=10s",
                PREFIX + "permitted-number-of-calls-in-half-open-state=1"};
    }

    private static String[] validWith(String override) {
        String key = override.substring(0, override.indexOf('=') + 1);
        List<String> values = new ArrayList<>(List.of(valid()));
        values.removeIf(value -> value.startsWith(key));
        values.add(override);
        return values.toArray(String[]::new);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
