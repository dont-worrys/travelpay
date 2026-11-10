package com.travelpay.gateway;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.List;

import com.travelpay.gateway.support.StubServer;
import com.travelpay.gateway.support.StubServer.Reply;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 배포 YAML과 HTTP 경로에서 외부 추적 정보와 브라우저 출처의 신뢰 경계를 확인합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        args = {"--spring.config.name=application-prod", "--spring.profiles.active=prod"}, properties = {
                "eureka.client.enabled=false",
                "management.tracing.sampling.probability=0.0",
                "logging.level.root=WARN"
        })
class GatewayBoundaryPolicyHttpTest {

    private static final String SESSION = "/internal/v1/auth/session";
    private static final String WALLET_PATH = "/api/v1/wallets/me";
    private static final String EXTERNAL_TRACE = "11111111111111111111111111111111";
    private static final String MEMBER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final StubServer USER = StubServer.start();
    private static final StubServer WALLET = StubServer.start();
    private static final List<String> EXTERNAL_TRACE_HEADERS = List.of("tracestate", "baggage", "b3",
            "X-B3-TraceId", "X-B3-SpanId", "X-B3-ParentSpanId", "X-B3-Sampled", "X-B3-Flags", "X-Amzn-Trace-Id");

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void serviceAddresses(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri", USER::uri);
        registry.add("spring.cloud.discovery.client.simple.instances.wallet-service[0].uri", WALLET::uri);
    }

    @BeforeEach
    void reset() {
        USER.reset();
        WALLET.reset();
        USER.reply(SESSION, Reply.json(200, """
                {"success":true,"data":{"userId":"%s","role":"USER"},"error":null}
                """.formatted(MEMBER_ID)));
    }

    @AfterAll
    static void stop() {
        USER.close();
        WALLET.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"w3c", "b3", "b3-multi"})
    void externalTraceSamplingAndBaggageDoNotBecomeTheInternalTrace(String format) {
        var request = client().get().uri(WALLET_PATH).header("Cookie", "SESSION=abc")
                .header("baggage", "account=external-private-value")
                .header("X-Amzn-Trace-Id", "Root=1-11111111-111111111111111111111111");
        switch (format) {
            case "w3c" -> request.header("traceparent", "00-" + EXTERNAL_TRACE + "-2222222222222222-01")
                    .header("tracestate", "vendor=external");
            case "b3" -> request.header("b3", EXTERNAL_TRACE + "-2222222222222222-1");
            case "b3-multi" -> request.header("X-B3-TraceId", EXTERNAL_TRACE)
                    .header("X-B3-SpanId", "2222222222222222").header("X-B3-Sampled", "1");
            default -> throw new AssertionError(format);
        }

        request.exchange().expectStatus().isOk();

        assertInternalTrace();
    }

    @Test
    void connectionOptionsCannotEraseTheNewInternalTrace() throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET " + WALLET_PATH + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Cookie: SESSION=abc\r\ntraceparent: 00-" + EXTERNAL_TRACE + "-2222222222222222-01\r\n"
                    + "Connection: close, Traceparent, Tracestate, Baggage, B3, X-B3-TraceId\r\n\r\n")
                    .getBytes(ISO_8859_1));
            var response = new String(socket.getInputStream().readAllBytes(), UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 200"), response);
        }
        assertInternalTrace();
    }

    private static void assertInternalTrace() {
        var verification = USER.received(SESSION).getFirst();
        var business = WALLET.received(WALLET_PATH).getFirst();
        assertEquals(1, verification.header("traceparent").size());
        assertEquals(1, business.header("traceparent").size());
        var verificationTrace = verification.header("traceparent").getFirst().split("-");
        var businessTrace = business.header("traceparent").getFirst().split("-");
        assertNotEquals(EXTERNAL_TRACE, verificationTrace[1]);
        assertEquals(verificationTrace[1], businessTrace[1]);
        assertNotEquals(verificationTrace[2], businessTrace[2]);
        // sampled는 최하위 비트입니다. 다른 정상 플래그까지 0이라고 가정하지 않습니다.
        assertEquals(0, Integer.parseInt(verificationTrace[3], 16) & 1);
        assertEquals(0, Integer.parseInt(businessTrace[3], 16) & 1);
        for (var header : EXTERNAL_TRACE_HEADERS) {
            assertTrue(verification.header(header).isEmpty(), header);
            assertTrue(business.header(header).isEmpty(), header);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://travelpay.store", "https://www.travelpay.store"})
    void aConfiguredFrontendCanPreflightWithoutASession(String origin) {
        client().options().uri(WALLET_PATH)
                .header("Origin", origin).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type,Csrf-Token,Idempotency-Key")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", origin)
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true")
                .expectHeader().valueMatches("Access-Control-Allow-Methods", ".*POST.*")
                .expectHeader().doesNotExist("Set-Cookie")
                .expectBody().isEmpty();

        assertTrue(USER.received().isEmpty());
        assertTrue(WALLET.received().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://travelpay.store", "https://www.travelpay.store"})
    void aCrossOriginRequestStillVerifiesTheSessionAndForwardsOnlyTheMember(String origin) {
        client().get().uri(WALLET_PATH).header("Origin", origin).header("Cookie", "SESSION=abc")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", origin)
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true");

        assertEquals(List.of("SESSION=abc"), USER.received(SESSION).getFirst().header("Cookie"));
        assertEquals(List.of("USER"), WALLET.received(WALLET_PATH).getFirst().header("User-Role"));
        assertTrue(WALLET.received(WALLET_PATH).getFirst().header("Cookie").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://travelpay.store.evil.test", "https://evil.test", "http://travelpay.store",
            "null", "https://project.vercel.app"})
    void anUnlistedOriginIsRejectedBeforeCallingServices(String origin) {
        client().get().uri(WALLET_PATH).header("Origin", origin).header("Cookie", "SESSION=abc")
                .exchange().expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin")
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.error.code").isEqualTo("COMMON_FORBIDDEN");

        assertTrue(USER.received().isEmpty());
        assertTrue(WALLET.received().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"User-Id", "Authorization"})
    void aPreflightCannotApproveInternalIdentityOrUnusedAuthenticationHeaders(String header) {
        client().options().uri(WALLET_PATH).header("Origin", "https://travelpay.store")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", header)
                .exchange().expectStatus().isForbidden()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.error.code").isEqualTo("COMMON_FORBIDDEN");

        assertTrue(USER.received().isEmpty());
        assertTrue(WALLET.received().isEmpty());
    }

    @Test
    void aPreflightCannotApproveAnUnsupportedMethod() {
        client().options().uri(WALLET_PATH).header("Origin", "https://travelpay.store")
                .header("Access-Control-Request-Method", "TRACE").exchange().expectStatus().isForbidden()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.error.code").isEqualTo("COMMON_FORBIDDEN");

        assertTrue(USER.received().isEmpty());
        assertTrue(WALLET.received().isEmpty());
    }

    @Test
    void aNormalOptionsRequestWithAnOriginStillNeedsSessionVerification() {
        client().options().uri(WALLET_PATH).header("Origin", "https://travelpay.store")
                .header("Cookie", "SESSION=abc").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://travelpay.store");

        assertEquals(List.of("OPTIONS"), USER.received(SESSION).getFirst().header("Original-Method"));
        assertEquals("OPTIONS", WALLET.received(WALLET_PATH).getFirst().method());
    }

    @Test
    void anAllowedOriginCanReadTheAuthenticationFailureWithoutLosingItsCookie() {
        USER.reply(SESSION, Reply.json(401, """
                {"success":false,"data":null,"error":{"code":"COMMON_UNAUTHORIZED","message":"m","details":[]}}
                """));

        client().get().uri(WALLET_PATH).header("Origin", "https://travelpay.store")
                .exchange().expectStatus().isUnauthorized()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://travelpay.store")
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().doesNotExist("Set-Cookie")
                .expectBody().jsonPath("$.error.code").isEqualTo("COMMON_UNAUTHORIZED");

        assertTrue(WALLET.received().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal/v1/auth/session", "/actuator/health"})
    void anExternalOriginIsNotApprovedForInternalOrManagementPaths(String path) {
        client().options().uri(path).header("Origin", "https://travelpay.store")
                .header("Access-Control-Request-Method", "POST").exchange()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");

        assertTrue(USER.received().isEmpty());
        assertTrue(WALLET.received().isEmpty());
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }
}
