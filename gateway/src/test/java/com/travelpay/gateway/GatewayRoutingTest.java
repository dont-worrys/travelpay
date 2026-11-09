package com.travelpay.gateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.travelpay.gateway.support.StubServer;
import com.travelpay.gateway.support.StubServer.Reply;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 설정 파일(application.yml)의 라우트와 필터로 Gateway를 띄우고, 다른 서비스 자리에 가짜 서버를 둬서
 * 브라우저 요청이 어떤 헤더로 어느 서비스에 도착하는지 확인합니다. Eureka 대신 고정 주소로 서비스를 찾습니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "GATEWAY_CORS_ALLOWED_ORIGINS=http://localhost:4173",
        "travelpay.gateway.session-verification.response-timeout=500ms",
        "logging.level.root=WARN"
})
class GatewayRoutingTest {

    private static final String SESSION_VERIFICATION = "/internal/v1/auth/session";
    private static final UUID MEMBER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final StubServer USER = StubServer.start();
    private static final StubServer WALLET = StubServer.start();
    private static final StubServer PAYMENT = StubServer.start();
    private static final StubServer EXCHANGE = StubServer.start();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void servicesOnStubServers(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri", USER::uri);
        registry.add("spring.cloud.discovery.client.simple.instances.wallet-service[0].uri", WALLET::uri);
        registry.add("spring.cloud.discovery.client.simple.instances.payment-service[0].uri", PAYMENT::uri);
        registry.add("spring.cloud.discovery.client.simple.instances.exchange-service[0].uri", EXCHANGE::uri);
    }

    @BeforeEach
    void reset() {
        List.of(USER, WALLET, PAYMENT, EXCHANGE).forEach(StubServer::reset);
        USER.reply(SESSION_VERIFICATION, Reply.json(200, """
                {"success":true,"data":{"userId":"%s","role":"USER"},"error":null}
                """.formatted(MEMBER_ID)));
    }

    @AfterAll
    static void stop() {
        List.of(USER, WALLET, PAYMENT, EXCHANGE).forEach(StubServer::close);
    }

    @Test
    void aPublicRequestSkipsSessionVerificationAndLosesHeadersTheBrowserMadeUp() {
        client().get().uri("/api/v1/terms")
                .header("User-Id", UUID.randomUUID().toString()).header("User-Role", "MASTER")
                .header("Original-Method", "POST").header("Request-Id", "browser-made-id")
                .exchange().expectStatus().isOk();

        assertTrue(USER.received(SESSION_VERIFICATION).isEmpty());
        var arrived = USER.received("/api/v1/terms").getFirst();
        assertTrue(arrived.header("User-Id").isEmpty());
        assertTrue(arrived.header("User-Role").isEmpty());
        assertTrue(arrived.header("Original-Method").isEmpty());
        var requestId = arrived.header("Request-Id");
        assertEquals(1, requestId.size());
        assertNotEquals("browser-made-id", requestId.getFirst());
        UUID.fromString(requestId.getFirst());
    }

    @Test
    void aProtectedRequestCarriesTheMemberFromSessionVerificationButNotTheCookie() {
        client().get().uri("/api/v1/wallets/me")
                .header("Cookie", "SESSION=abc")
                .header("User-Id", UUID.randomUUID().toString()).header("User-Role", "MASTER")
                .exchange().expectStatus().isOk();

        var verification = USER.received(SESSION_VERIFICATION).getFirst();
        assertEquals(List.of("SESSION=abc"), verification.header("Cookie"));
        assertEquals(List.of("GET"), verification.header("Original-Method"));
        var arrived = WALLET.received("/api/v1/wallets/me").getFirst();
        assertEquals(List.of(MEMBER_ID.toString()), arrived.header("User-Id"));
        assertEquals(List.of("USER"), arrived.header("User-Role"));
        assertTrue(arrived.header("Cookie").isEmpty());
        assertEquals(verification.header("Request-Id"), arrived.header("Request-Id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "close, User-Id, User-Role, Request-Id, Client-Ip",
            "close, user-id, user-role, request-id, client-ip",
            "close, uSeR-iD, USER-ROLE, rEQUEST-iD, CLIENT-IP"
    })
    void connectionOptionsCannotEraseTheIdentityAndIpGatewaySupplies(String connection) throws IOException {
        var response = raw("GET", "/api/v1/wallets/me", connection);

        assertTrue(response.startsWith("HTTP/1.1 200"), response);
        var verification = USER.received(SESSION_VERIFICATION).getFirst();
        assertEquals(List.of("SESSION=abc"), verification.header("Cookie"));
        var arrived = WALLET.received("/api/v1/wallets/me").getFirst();
        assertEquals(List.of(MEMBER_ID.toString()), arrived.header("User-Id"));
        assertEquals(List.of("USER"), arrived.header("User-Role"));
        assertEquals(List.of("127.0.0.1"), arrived.header("Client-Ip"));
        assertEquals(1, arrived.header("Request-Id").size());
        UUID.fromString(arrived.header("Request-Id").getFirst());
        assertEquals(verification.header("Request-Id"), arrived.header("Request-Id"));
        assertTrue(arrived.header("Cookie").isEmpty());
    }

    @Test
    void connectionOptionsCannotEraseGatewayIpAndRequestIdOnPublicUserRoutes() throws IOException {
        var response = raw("GET", "/api/v1/terms", "close, Client-Ip, Request-Id");

        assertTrue(response.startsWith("HTTP/1.1 200"), response);
        assertTrue(USER.received(SESSION_VERIFICATION).isEmpty());
        var arrived = USER.received("/api/v1/terms").getFirst();
        assertEquals(List.of("127.0.0.1"), arrived.header("Client-Ip"));
        assertEquals(1, arrived.header("Request-Id").size());
        UUID.fromString(arrived.header("Request-Id").getFirst());
        assertTrue(arrived.header("User-Id").isEmpty());
        assertTrue(arrived.header("User-Role").isEmpty());
    }

    @Test
    void theSessionCheckCallContinuesTheTraceOfTheRequest() {
        client().get().uri("/api/v1/wallets/me").header("Cookie", "SESSION=abc").exchange().expectStatus().isOk();

        var verification = USER.received(SESSION_VERIFICATION).getFirst().header("traceparent");
        var arrived = WALLET.received("/api/v1/wallets/me").getFirst().header("traceparent");
        assertEquals(1, arrived.size());
        assertEquals(1, verification.size());
        assertEquals(arrived.getFirst().split("-")[1], verification.getFirst().split("-")[1]);
    }

    @Test
    void aStateChangingRequestSendsItsMethodAndCsrfTokenToSessionVerification() {
        client().post().uri("/api/v1/transfers")
                .header("Cookie", "SESSION=abc").header("Csrf-Token", "csrf-1")
                .header("Content-Type", "application/json").bodyValue("{}")
                .exchange().expectStatus().isOk();

        var verification = USER.received(SESSION_VERIFICATION).getFirst();
        assertEquals(List.of("POST"), verification.header("Original-Method"));
        assertEquals(List.of("csrf-1"), verification.header("Csrf-Token"));
        var arrived = PAYMENT.received("/api/v1/transfers").getFirst();
        assertEquals(List.of(MEMBER_ID.toString()), arrived.header("User-Id"));
        assertTrue(arrived.header("Cookie").isEmpty());
    }

    // 관리자 API도 User 라우트로 같은 방식(세션 확인 뒤 쿠키와 함께)으로 전달됨
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/users/me", "/api/v1/admin/users", "/api/v1/admin/terms"})
    void userServiceStillReceivesTheCookieOnItsProtectedRoutes(String path) {
        client().get().uri(path).header("Cookie", "SESSION=abc").exchange().expectStatus().isOk();

        assertEquals(1, USER.received(SESSION_VERIFICATION).size());
        var arrived = USER.received(path).getFirst();
        assertEquals(List.of("SESSION=abc"), arrived.header("Cookie"));
        assertEquals(List.of(MEMBER_ID.toString()), arrived.header("User-Id"));
    }

    @Test
    void logoutSkipsSessionVerificationSoSuspendedMembersCanStillLogOut() {
        client().post().uri("/api/v1/auth/logout")
                .header("Cookie", "SESSION=abc").header("Csrf-Token", "csrf-1")
                .exchange().expectStatus().isOk();

        assertTrue(USER.received(SESSION_VERIFICATION).isEmpty());
        var arrived = USER.received("/api/v1/auth/logout").getFirst();
        assertEquals(List.of("SESSION=abc"), arrived.header("Cookie"));
        assertEquals(List.of("csrf-1"), arrived.header("Csrf-Token"));
    }

    @Test
    void exchangeRatesArePublicAndStillLoseTheCookie() {
        client().get().uri("/api/v1/exchange-rates").header("Cookie", "SESSION=abc").exchange().expectStatus().isOk();

        assertTrue(USER.received(SESSION_VERIFICATION).isEmpty());
        assertTrue(EXCHANGE.received("/api/v1/exchange-rates").getFirst().header("Cookie").isEmpty());
    }

    /** 환전 관리자 API는 세션 확인을 거쳐, 확인된 회원 헤더를 달고 환전 서비스에 도착합니다. 쿠키는 넘기지 않습니다. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/admin/exchanges/manual-reviews",
            "/api/v1/admin/exchanges/resolutions",
            "/api/v1/admin/exchanges/550e8400-e29b-41d4-a716-446655440001"
    })
    void adminExchangeRequestsReachExchangeServiceWithTheVerifiedMaster(String path) {
        USER.reply(SESSION_VERIFICATION, Reply.json(200, """
                {"success":true,"data":{"userId":"%s","role":"MASTER"},"error":null}
                """.formatted(MEMBER_ID)));

        client().get().uri(path).header("Cookie", "SESSION=abc").exchange().expectStatus().isOk();

        assertEquals(List.of("SESSION=abc"), USER.received(SESSION_VERIFICATION).getFirst().header("Cookie"));
        var arrived = EXCHANGE.received(path).getFirst();
        assertEquals(List.of(MEMBER_ID.toString()), arrived.header("User-Id"));
        assertEquals(List.of("MASTER"), arrived.header("User-Role"));
        assertTrue(arrived.header("Cookie").isEmpty());
    }

    /** 처리(POST)도 같은 라우트로 가며, 세션 확인에 메서드를 알립니다. */
    @Test
    void adminExchangeResolveReachesExchangeService() {
        String path = "/api/v1/admin/exchanges/550e8400-e29b-41d4-a716-446655440001/resolve";

        client().post().uri(path).header("Cookie", "SESSION=abc").header("Csrf-Token", "csrf")
                .exchange().expectStatus().isOk();

        assertEquals(List.of("POST"), USER.received(SESSION_VERIFICATION).getFirst().header("Original-Method"));
        assertEquals(1, EXCHANGE.received(path).size());
    }

    /** 일반 회원이 User-Role: MASTER를 직접 보내도 Gateway가 세션의 역할(USER)로 덮어씁니다. 관리자 권한은 이 헤더에 기댑니다. */
    @Test
    void aMemberCannotClaimMasterOnAdminExchangeRoutes() {
        client().get().uri("/api/v1/admin/exchanges/manual-reviews")
                .header("Cookie", "SESSION=abc")
                .header("User-Id", UUID.randomUUID().toString()).header("User-Role", "MASTER")
                .exchange().expectStatus().isOk();

        var arrived = EXCHANGE.received("/api/v1/admin/exchanges/manual-reviews").getFirst();
        assertEquals(List.of(MEMBER_ID.toString()), arrived.header("User-Id"));
        assertEquals(List.of("USER"), arrived.header("User-Role"));
    }

    @Test
    void theConfiguredLocalFrontendCanPreflightWithoutSessionVerification() {
        client().options().uri("/api/v1/transfers").header("Origin", "http://localhost:4173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Csrf-Token,Idempotency-Key,Content-Type")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:4173")
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true")
                .expectBody().isEmpty();

        List.of(USER, WALLET, PAYMENT, EXCHANGE).forEach(stub -> assertTrue(stub.received().isEmpty()));
    }

    @ParameterizedTest
    @CsvSource({"401,COMMON_UNAUTHORIZED", "403,USER_ACCOUNT_SUSPENDED", "403,COMMON_CSRF_INVALID",
            "409,COMMON_CONFLICT"})
    void aRejectionFromSessionVerificationReachesTheBrowserAsItIs(int status, String code) {
        var body = """
                {"success":false,"data":null,"error":{"code":"%s","message":"m","details":[]}}
                """.formatted(code);
        USER.reply(SESSION_VERIFICATION, Reply.json(status, body));

        var result = client().get().uri("/api/v1/wallets/me").header("Cookie", "SESSION=abc")
                .exchange().expectStatus().isEqualTo(status)
                .expectHeader().contentType("application/json")
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().doesNotExist("Set-Cookie")
                .expectBody().returnResult();

        assertEquals(MAPPER.readTree(body), MAPPER.readTree(new String(result.getResponseBodyContent(), UTF_8)));
        assertTrue(WALLET.received().isEmpty());
    }

    @Test
    void aRejectedHeadRequestGetsTheStatusWithoutABody() throws IOException {
        USER.reply(SESSION_VERIFICATION, Reply.json(401, """
                {"success":false,"data":null,"error":{"code":"COMMON_UNAUTHORIZED","message":"m","details":[]}}
                """));

        var response = raw("HEAD", "/api/v1/wallets/me");

        assertTrue(response.startsWith("HTTP/1.1 401"), response);
        assertTrue(response.lines().anyMatch(line -> line.equalsIgnoreCase("cache-control: no-store")), response);
        assertTrue(response.endsWith("\r\n\r\n"), response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"server-error", "not-json", "slow"})
    void anAuthenticationOutageIs503AndLeavesTheCookieAlone(String outage) {
        USER.reply(SESSION_VERIFICATION, switch (outage) {
            case "server-error" -> Reply.json(500, "");
            case "not-json" -> Reply.json(200, "<html>oops</html>");
            default -> Reply.json(200, "{}").delayedBy(Duration.ofMillis(1500));
        });

        var result = client().get().uri("/api/v1/wallets/me").header("Cookie", "SESSION=abc")
                .exchange().expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().doesNotExist("Set-Cookie")
                .expectBody().returnResult();

        assertEquals("COMMON_SERVICE_UNAVAILABLE", MAPPER.readTree(new String(result.getResponseBodyContent(), UTF_8))
                .get("error").get("code").asString());
        assertTrue(WALLET.received().isEmpty());
    }

    @Test
    void aHeadAuthenticationOutageHasNoBodyAndCannotBeStored() throws IOException {
        USER.reply(SESSION_VERIFICATION, Reply.json(500, ""));

        var response = raw("HEAD", "/api/v1/wallets/me");

        assertTrue(response.startsWith("HTTP/1.1 503"), response);
        assertTrue(response.lines().anyMatch(line -> line.equalsIgnoreCase("cache-control: no-store")), response);
        assertTrue(response.endsWith("\r\n\r\n"), response);
        assertTrue(WALLET.received().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "\"1\""})
    void aNumericRoleInSessionVerificationStopsTheRequestBeforeWallet(String role) {
        USER.reply(SESSION_VERIFICATION, Reply.json(200, """
                {"success":true,"data":{"userId":"%s","role":%s},"error":null}
                """.formatted(MEMBER_ID, role)));

        var result = client().get().uri("/api/v1/wallets/me").header("Cookie", "SESSION=abc")
                .exchange().expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().doesNotExist("Set-Cookie")
                .expectBody().returnResult();

        assertEquals("COMMON_SERVICE_UNAVAILABLE", MAPPER.readTree(new String(result.getResponseBodyContent(), UTF_8))
                .get("error").get("code").asString());
        assertTrue(WALLET.received().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal/v1/auth/session", "/internal/v1/users/550e8400-e29b-41d4-a716-446655440000/real-name",
            "/internal/v1/ledger/accounts", "/actuator/env"})
    void internalAndUnroutedPathsAreNotReachable(String path) {
        client().get().uri(path).header("Cookie", "SESSION=abc").exchange().expectStatus().isNotFound()
                .expectHeader().valueEquals("Cache-Control", "no-store");

        List.of(USER, WALLET, PAYMENT, EXCHANGE).forEach(stub -> assertTrue(stub.received().isEmpty()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE"})
    void eachMethodAdvertisedByTheGatewayCanReachSessionVerificationAndTheService(String method) throws IOException {
        WALLET.reply("/api/v1/wallets/me", Reply.json(200, ""));

        var response = raw(method, "/api/v1/wallets/me");

        assertTrue(response.startsWith("HTTP/1.1 200"), response);
        assertEquals(List.of(method), USER.received(SESSION_VERIFICATION).getFirst().header("Original-Method"));
        assertEquals(method, WALLET.received("/api/v1/wallets/me").getFirst().method());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRACE", "PROPFIND", "get"})
    void anUnsupportedMethodReturns405AndAllowBeforeCallingServices(String method) throws IOException {
        var response = raw(method, "/api/v1/wallets/me");

        assertTrue(response.startsWith("HTTP/1.1 405"), response);
        assertTrue(response.contains("COMMON_METHOD_NOT_ALLOWED"), response);
        var allow = response.substring(0, response.indexOf("\r\n\r\n")).lines()
                .filter(line -> line.regionMatches(true, 0, "Allow:", 0, 6))
                .flatMap(line -> Arrays.stream(line.substring(6).split(",")))
                .map(String::strip)
                .collect(Collectors.toSet());
        assertEquals(Set.of("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE"), allow, response);
        assertTrue(response.lines().anyMatch(line -> line.equalsIgnoreCase("cache-control: no-store")), response);
        List.of(USER, WALLET, PAYMENT, EXCHANGE).forEach(stub -> assertTrue(stub.received().isEmpty()));
    }

    /** 브라우저·HTTP 클라이언트는 보내기 전에 경로를 정리하므로, 소켓으로 받은 그대로의 경로를 보냅니다. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/exchange-rates/../exchanges",
            "/api/v1/exchange-rates/%2e%2e/exchanges",
            "/api/v1/exchange-rates/./latest",
            "/api/v1/exchange-rates/..;/exchanges",
            "/api/v1/exchange-rates;x=1/latest",
            "/api/v1/wallets/..%2f..%2finternal/v1/wallets",
            "/api/v1/wallets/..%5c..%5cinternal/v1/wallets"
    })
    void aPathThatIsNotNormalizedIs400BeforeAnythingElse(String path) throws IOException {
        var response = raw("GET", path);

        assertTrue(response.startsWith("HTTP/1.1 400"), response);
        assertTrue(response.contains("COMMON_INVALID_REQUEST"), response);
        List.of(USER, WALLET, PAYMENT, EXCHANGE).forEach(stub -> assertTrue(stub.received().isEmpty()));
    }

    @Test
    void theClientIpIsTheRealAddressAndIpHeadersTheBrowserSentAreDropped() {
        client().post().uri("/api/v1/auth/login").header("X-Forwarded-For", "203.0.113.99")
                .header("Client-Ip", "203.0.113.98").header("CF-Connecting-IP", "203.0.113.97")
                .header("Content-Type", "application/json").bodyValue("{}")
                .exchange().expectStatus().isOk();

        var arrived = USER.received("/api/v1/auth/login").getFirst();
        assertEquals(List.of("127.0.0.1"), arrived.header("Client-Ip"));
        assertTrue(arrived.header("CF-Connecting-IP").isEmpty());
        assertTrue(arrived.header("X-Forwarded-For").isEmpty());
    }

    private String raw(String method, String path) throws IOException {
        return raw(method, path, "close");
    }

    private String raw(String method, String path, String connection) throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Cookie: SESSION=abc\r\nConnection: " + connection + "\r\n\r\n").getBytes(ISO_8859_1));
            var response = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(response);
            return response.toString(UTF_8);
        }
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }
}
