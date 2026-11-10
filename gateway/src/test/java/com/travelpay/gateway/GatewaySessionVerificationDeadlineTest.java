package com.travelpay.gateway;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 실제 Gateway와 조금씩 응답하는 User 대역으로 세션 검증 전체의 대기 상한을 확인합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "GATEWAY_SESSION_VERIFICATION_CONNECT_TIMEOUT=200ms",
        "GATEWAY_SESSION_VERIFICATION_RESPONSE_TIMEOUT=5s",
        "GATEWAY_SESSION_VERIFICATION_TOTAL_TIMEOUT=1200ms",
        "logging.level.root=WARN"
})
class GatewaySessionVerificationDeadlineTest {

    private static final String SESSION_VERIFICATION = "/internal/v1/auth/session";
    private static final String WALLET_PATH = "/api/v1/wallets/me";
    private static final UUID MEMBER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final AtomicBoolean TRICKLE = new AtomicBoolean();
    private static final AtomicBoolean SLOW_BUSINESS = new AtomicBoolean();
    private static final AtomicInteger VERIFICATION_STATUS = new AtomicInteger(200);
    private static final AtomicInteger VERIFICATION_CALLS = new AtomicInteger();
    private static final AtomicInteger WALLET_CALLS = new AtomicInteger();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final DisposableServer SERVICES = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> {
                response.header("Content-Type", "application/json");
                if (request.fullPath().equals(SESSION_VERIFICATION)) {
                    VERIFICATION_CALLS.incrementAndGet();
                    int status = VERIFICATION_STATUS.get();
                    response.status(status);
                    var body = status == 200
                            ? "{\"success\":true,\"data\":{\"userId\":\"" + MEMBER_ID
                                    + "\",\"role\":\"USER\"},\"error\":null}"
                            : "{\"success\":false,\"data\":null,\"error\":{\"code\":\"COMMON_UNAUTHORIZED\","
                                    + "\"message\":\"로그인이 필요합니다.\",\"details\":[]}}";
                    if (TRICKLE.get()) {
                        // 매 100ms마다 전송하므로 읽기 간격 5초를 넘지 않지만, 전체 본문은 2.4초 걸립니다.
                        var chunks = Flux.interval(Duration.ofMillis(100)).take(24)
                                .map(index -> Unpooled.copiedBuffer(index == 23 ? body : " ", UTF_8));
                        return response.send(chunks, buffer -> true).then();
                    }
                    return response.sendString(Mono.just(body)).then();
                }
                if (!request.fullPath().equals(WALLET_PATH) && !request.fullPath().equals("/api/v1/terms")) {
                    return response.status(404).send();
                }
                if (request.fullPath().equals(WALLET_PATH)) {
                    WALLET_CALLS.incrementAndGet();
                }
                var delay = SLOW_BUSINESS.get() ? Duration.ofSeconds(2) : Duration.ZERO;
                return Mono.delay(delay).then(response.sendString(Mono.just(
                        "{\"success\":true,\"data\":{\"from\":\"service\"},\"error\":null}")).then());
            }).bindNow();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void servicesOnTheHttpServer(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri",
                () -> "http://127.0.0.1:" + SERVICES.port());
        registry.add("spring.cloud.discovery.client.simple.instances.wallet-service[0].uri",
                () -> "http://127.0.0.1:" + SERVICES.port());
    }

    @BeforeEach
    void resetAndWarmTheRoute() {
        TRICKLE.set(false);
        SLOW_BUSINESS.set(false);
        VERIFICATION_STATUS.set(200);
        VERIFICATION_CALLS.set(0);
        WALLET_CALLS.set(0);
        client().get().uri("/api/v1/terms").exchange().expectStatus().isOk();
    }

    @AfterAll
    static void stop() {
        SERVICES.disposeNow();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 401})
    void aTricklingVerificationResponseCannotKeepTheGatewayWaitingPastTheTotalBudget(int status) {
        VERIFICATION_STATUS.set(status);
        TRICKLE.set(true);

        var result = client().get().uri(WALLET_PATH).header("Cookie", "SESSION=abc")
                .exchange().expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().doesNotExist("Set-Cookie")
                .expectBody().returnResult();

        assertEquals("COMMON_SERVICE_UNAVAILABLE", MAPPER.readTree(result.getResponseBodyContent())
                .get("error").get("code").asString());
        assertEquals(1, VERIFICATION_CALLS.get());
        assertEquals(0, WALLET_CALLS.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/terms", WALLET_PATH})
    void theVerificationBudgetDoesNotLimitPublicRequestsOrBusinessProcessing(String path) {
        SLOW_BUSINESS.set(true);

        client().get().uri(path).header("Cookie", "SESSION=abc").exchange().expectStatus().isOk();

        assertEquals(path.equals(WALLET_PATH) ? 1 : 0, VERIFICATION_CALLS.get());
        assertEquals(path.equals(WALLET_PATH) ? 1 : 0, WALLET_CALLS.get());
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(5)).build();
    }
}
