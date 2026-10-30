package com.travelpay.user.common.config;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginAttemptPropertiesTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Duration BLOCK = Duration.ofMinutes(20);

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void environmentConfigurationBindsTheAgreedAttemptLimits(String filename) throws IOException {
        var environment = new MockEnvironment();
        var sources = new YamlPropertySourceLoader().load(filename, new ClassPathResource(filename));
        sources.forEach(source -> environment.getPropertySources().addLast(source));

        var properties = Binder.get(environment)
                .bindOrCreate("travelpay.user.login-attempt", LoginAttemptProperties.class);

        assertEquals(new LoginAttemptProperties(5, 20, WINDOW, BLOCK), properties);
    }

    @ParameterizedTest
    @CsvSource({"0, 20", "5, 0", "-1, 20"})
    void rejectsLimitsBelowOne(int pairLimit, int accountLimit) {
        assertThrows(IllegalArgumentException.class,
                () -> new LoginAttemptProperties(pairLimit, accountLimit, WINDOW, BLOCK));
    }

    @ParameterizedTest
    @CsvSource({"PT0S, PT20M", "PT10M, PT0S", "-PT1S, PT20M", "PT10M, -PT1S"})
    void rejectsNonPositiveDurations(Duration window, Duration block) {
        assertThrows(IllegalArgumentException.class, () -> new LoginAttemptProperties(5, 20, window, block));
    }

    @ParameterizedTest
    @CsvSource({"PT0.999S, PT20M", "PT10M, PT0.5S", "PT0.0005S, PT20M"})
    void rejectsDurationsShorterThanOneSecondBecauseTheyDisableTheLimit(Duration window, Duration block) {
        assertThrows(IllegalArgumentException.class, () -> new LoginAttemptProperties(5, 20, window, block));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "500us"})
    void rejectsAWindowWrittenWithoutAUnitOrBelowAMillisecond(String window) {
        var source = new MapConfigurationPropertySource(Map.of(
                "travelpay.user.login-attempt.pair-limit", "5",
                "travelpay.user.login-attempt.account-limit", "20",
                "travelpay.user.login-attempt.window", window,
                "travelpay.user.login-attempt.block", "20m"));

        // 단위가 없는 10은 10분이 아니라 10ms로 읽히므로, 시작 단계에서 막혀야 합니다.
        assertThrows(BindException.class,
                () -> new Binder(source).bind("travelpay.user.login-attempt", LoginAttemptProperties.class));
    }
}
