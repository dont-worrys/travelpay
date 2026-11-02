package com.travelpay.gateway.request;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.web.cors.CorsConfiguration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayCorsConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://travelpay.store", "https://www.travelpay.store", "http://localhost:4173"})
    void anExactHttpsOrLocalOriginIsAccepted(String origin) {
        assertDoesNotThrow(() -> new GatewayCorsConfiguration(properties(List.of(origin))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.travelpay.store", "null", "http://travelpay.store",
            "https://travelpay.store/path", "https://user:password@travelpay.store", "https://travelpay.store?x=y",
            "https://travelpay.store#fragment", "https://travelpay.store:65536"})
    void anAmbiguousOrUnsafeOriginStopsStartup(String origin) {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> new GatewayCorsConfiguration(properties(List.of(origin))));

        assertTrue(exception.getMessage().contains("globalcors"));
    }

    @Test
    void anEmptyListAllowsTheApplicationToRunWithoutCrossOriginAccess() {
        assertDoesNotThrow(() -> new GatewayCorsConfiguration(properties(List.of())));
    }

    @Test
    void originPatternsCannotBroadenTheExactOriginPolicy() {
        var properties = properties(List.of());
        properties.getCorsConfigurations().get("/api/v1/**")
                .setAllowedOriginPatterns(List.of("https://*.travelpay.store"));

        assertThrows(IllegalArgumentException.class, () -> new GatewayCorsConfiguration(properties));
    }

    private static GlobalCorsProperties properties(List<String> origins) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowCredentials(true);
        var properties = new GlobalCorsProperties();
        properties.getCorsConfigurations().put("/api/v1/**", configuration);
        return properties;
    }
}
