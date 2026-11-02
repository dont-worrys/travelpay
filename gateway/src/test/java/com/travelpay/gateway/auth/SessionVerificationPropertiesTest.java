package com.travelpay.gateway.auth;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 앱이 시작할 때처럼 Spring Boot의 Binder로 설정값을 채워 확인합니다. */
class SessionVerificationPropertiesTest {

    private static final String PREFIX = "travelpay.gateway.session-verification";

    @Test
    void theConfiguredValuesBindToTheRecord() {
        var properties = bind(settings());

        assertEquals(URI.create("http://user-service/internal/v1/auth/session"), properties.uri());
        assertEquals(Duration.ofSeconds(2), properties.connectTimeout());
        assertEquals(Duration.ofMillis(500), properties.responseTimeout());
        assertEquals(Duration.ofMillis(750), properties.totalTimeout());
    }

    @ParameterizedTest
    @CsvSource({"uri,", "connect-timeout,", "response-timeout,", "connect-timeout,0s", "response-timeout,-1s",
            "total-timeout,", "total-timeout,0s", "total-timeout,-1s"})
    void aMissingOrNonPositiveValueStopsStartupNamingTheSetting(String name, String value) {
        var settings = settings();
        if (value == null) {
            settings.remove(PREFIX + "." + name);
        }
        else {
            settings.put(PREFIX + "." + name, value);
        }

        var exception = assertThrows(BindException.class, () -> bind(settings));

        var reason = rootCause(exception).getMessage();
        assertTrue(reason != null && reason.contains(PREFIX + "." + name), reason);
    }

    private static Map<String, String> settings() {
        var settings = new HashMap<String, String>();
        settings.put(PREFIX + ".uri", "http://user-service/internal/v1/auth/session");
        settings.put(PREFIX + ".connect-timeout", "2s");
        settings.put(PREFIX + ".response-timeout", "500ms");
        settings.put(PREFIX + ".total-timeout", "750ms");
        return settings;
    }

    private static SessionVerificationProperties bind(Map<String, String> settings) {
        return new Binder(new MapConfigurationPropertySource(settings))
                .bindOrCreate(PREFIX, SessionVerificationProperties.class);
    }

    private static Throwable rootCause(Throwable failure) {
        var cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
