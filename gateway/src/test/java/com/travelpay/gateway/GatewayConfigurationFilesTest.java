package com.travelpay.gateway;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.travelpay.gateway.auth.SessionVerificationProperties;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.micrometer.tracing.autoconfigure.TracingProperties;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.cors.CorsConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 두 환경의 설정 파일은 따로 쓰이므로, 라우트·세션 확인·클라이언트 IP 설정이 한쪽에만 바뀌지 않았는지 확인합니다. */
class GatewayConfigurationFilesTest {

    private static final String[] SHARED_PREFIXES = {
            "spring.cloud.gateway.server.webflux.routes", "travelpay.gateway."};
    private static final String SESSION_CONFIG = "travelpay.gateway.session-verification";

    @Test
    void localAndProdHaveTheSameRoutesAndGatewaySettings() throws IOException {
        var local = shared("application.yml");

        assertFalse(local.isEmpty());
        assertEquals(local, shared("application-prod.yml"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void theEdgeStartsItsOwnTraceWithoutRemoteBaggage(String filename) throws IOException {
        var properties = Binder.get(environmentFor(filename)).bindOrCreate("management.tracing", TracingProperties.class);

        assertTrue(properties.getPropagation().getConsume().isEmpty());
        assertNull(properties.getPropagation().getType());
        assertEquals(List.of(TracingProperties.Propagation.PropagationType.W3C), properties.getPropagation().getProduce());
        assertFalse(properties.getBaggage().isEnabled());
    }

    @Test
    void localCorsWaitsForAnExplicitFrontendPort() throws IOException {
        var configuration = corsFor(environmentFor("application.yml"));

        assertTrue(configuration.getAllowedOrigins() == null || configuration.getAllowedOrigins().isEmpty());
    }

    @Test
    void prodCorsOnlyApprovesTheTwoHttpsFrontendsByDefault() throws IOException {
        var configuration = corsFor(environmentFor("application-prod.yml"));

        assertEquals(List.of("https://travelpay.store", "https://www.travelpay.store"), configuration.getAllowedOrigins());
        assertEquals(Boolean.TRUE, configuration.getAllowCredentials());
        assertEquals(List.of("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE"), configuration.getAllowedMethods());
        assertEquals(List.of("Accept", "Content-Type", "Csrf-Token", "Idempotency-Key"), configuration.getAllowedHeaders());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void corsOriginsCanBeSuppliedByTheExecutionEnvironment(String filename) throws IOException {
        var configuration = corsFor(environmentFor(filename)
                .withProperty("GATEWAY_CORS_ALLOWED_ORIGINS", "http://localhost:4173,https://preview.travelpay.store"));

        assertEquals(List.of("http://localhost:4173", "https://preview.travelpay.store"), configuration.getAllowedOrigins());
    }

    private static CorsConfiguration corsFor(MockEnvironment environment) {
        var properties = Binder.get(environment).bindOrCreate(
                "spring.cloud.gateway.server.webflux.globalcors", GlobalCorsProperties.class);
        var configuration = properties.getCorsConfigurations().get("/api/v1/**");
        assertNotNull(configuration);
        return configuration;
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void sessionVerificationHasSeparateConnectionReadAndTotalBudgets(String filename) throws IOException {
        var properties = Binder.get(environmentFor(filename))
                .bindOrCreate(SESSION_CONFIG, SessionVerificationProperties.class);

        assertEquals(Duration.ofSeconds(2), properties.connectTimeout());
        assertEquals(Duration.ofSeconds(3), properties.responseTimeout());
        assertEquals(Duration.ofSeconds(5), properties.totalTimeout());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void sessionVerificationBudgetsCanBeChangedIndependentlyByTheExecutionEnvironment(String filename) throws IOException {
        var environment = environmentFor(filename)
                .withProperty("GATEWAY_SESSION_VERIFICATION_CONNECT_TIMEOUT", "400ms")
                .withProperty("GATEWAY_SESSION_VERIFICATION_RESPONSE_TIMEOUT", "2s")
                .withProperty("GATEWAY_SESSION_VERIFICATION_TOTAL_TIMEOUT", "7s");
        var properties = Binder.get(environment).bindOrCreate(SESSION_CONFIG, SessionVerificationProperties.class);

        assertEquals(Duration.ofMillis(400), properties.connectTimeout());
        assertEquals(Duration.ofSeconds(2), properties.responseTimeout());
        assertEquals(Duration.ofSeconds(7), properties.totalTimeout());
    }

    @Test
    void localManagementDefaultsUseADifferentPortAndALoopbackAddress() throws IOException {
        var environment = environmentFor("application.yml");
        var apiPort = environment.getRequiredProperty("server.port", Integer.class);
        var managementPort = environment.getProperty("management.server.port", Integer.class);

        assertNotNull(managementPort);
        assertTrue(managementPort > 0 && managementPort <= 65535);
        assertNotEquals(apiPort, managementPort);
        assertTrue(InetAddress.getByName(environment.getRequiredProperty("management.server.address"))
                .isLoopbackAddress());
    }

    @Test
    void prodManagementDefaultsUseADifferentPortAndListenOnDeploymentInterfaces() throws IOException {
        var environment = environmentFor("application-prod.yml");
        var apiPort = environment.getRequiredProperty("server.port", Integer.class);
        var managementPort = environment.getProperty("management.server.port", Integer.class);

        assertNotNull(managementPort);
        assertTrue(managementPort > 0 && managementPort <= 65535);
        assertNotEquals(apiPort, managementPort);
        assertTrue(InetAddress.getByName(environment.getRequiredProperty("management.server.address"))
                .isAnyLocalAddress());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void managementAddressAndPortCanBeSuppliedByTheExecutionEnvironment(String filename) throws IOException {
        var environment = environmentFor(filename)
                .withProperty("GATEWAY_MANAGEMENT_PORT", "19080")
                .withProperty("GATEWAY_MANAGEMENT_ADDRESS", "192.0.2.10");

        assertEquals(19080, environment.getProperty("management.server.port", Integer.class));
        assertEquals("192.0.2.10", environment.getProperty("management.server.address"));
    }

    private static MockEnvironment environmentFor(String filename) throws IOException {
        var environment = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader().load(filename, new ClassPathResource(filename))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }

    private static Map<String, Object> shared(String filename) throws IOException {
        var values = new TreeMap<String, Object>();
        for (var source : new YamlPropertySourceLoader().load(filename, new ClassPathResource(filename))) {
            for (var name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
                for (var prefix : SHARED_PREFIXES) {
                    if (name.startsWith(prefix)) {
                        values.put(name, String.valueOf(source.getProperty(name)));
                    }
                }
            }
        }
        return values;
    }
}
