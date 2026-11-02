package com.travelpay.gateway;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.List;

import com.travelpay.gateway.support.StubServer;
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

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 배포 설정만 읽고 ALB 연결을 모사해, 전달 헤더가 연결 주소와 Cloudflare IP 판단을 바꾸지 못하게 합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        args = {"--spring.config.name=application-prod", "--spring.profiles.active=prod"},
        properties = {
                "spring.main.cloud-platform=KUBERNETES",
                "eureka.client.enabled=false",
                "travelpay.gateway.client-ip.proxy-addresses=127.0.0.1/32",
                "logging.level.root=WARN"
        })
class GatewayProdAlbClientIpTest {

    private static final StubServer USER = StubServer.start();
    // 외부 입력·방문자·Cloudflare의 주소가 섞인 목록. 실제 ALB는 기본 append 모드로 마지막 연결 상대를 추가합니다.
    private static final String FORWARDED_CHAIN = "203.0.113.99, 203.0.113.50, 173.245.48.2";

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void userServiceOnAStubServer(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri", USER::uri);
    }

    @BeforeEach
    void reset() {
        USER.reset();
    }

    @AfterAll
    static void stop() {
        USER.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Forwarded-For", "Forwarded"})
    void aTrustedAlbConnectionUsesTheCloudflareIpInsteadOfAnExternalForwardedValue(String headerName) {
        var headerValue = headerName.equals("X-Forwarded-For") ? FORWARDED_CHAIN : "for=203.0.113.99";
        client().get().uri("/api/v1/terms")
                .header(headerName, headerValue)
                .header("CF-Connecting-IP", "203.0.113.50")
                .header("Client-Ip", "203.0.113.98")
                .exchange().expectStatus().isOk();

        var arrived = USER.received("/api/v1/terms").getFirst();
        assertEquals(List.of("203.0.113.50"), arrived.header("Client-Ip"));
        assertTrue(arrived.header("CF-Connecting-IP").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"2001:db8::50,2001:db8::50", "not-an-ip,127.0.0.1"})
    void aCloudflareIpv6IsKeptAndAnInvalidIpFallsBackToTheAlbConnection(String cloudflareIp, String expected) {
        client().get().uri("/api/v1/terms")
                .header("X-Forwarded-For", FORWARDED_CHAIN)
                .header("CF-Connecting-IP", cloudflareIp)
                .exchange().expectStatus().isOk();

        assertEquals(List.of(expected), USER.received("/api/v1/terms").getFirst().header("Client-Ip"));
    }

    @Test
    void withoutACloudflareIpTheConnectionAddressIsUsedInsteadOfTheForwardedList() {
        client().get().uri("/api/v1/terms")
                .header("X-Forwarded-For", FORWARDED_CHAIN)
                .exchange().expectStatus().isOk();

        assertEquals(List.of("127.0.0.1"), USER.received("/api/v1/terms").getFirst().header("Client-Ip"));
    }

    @Test
    void repeatedCloudflareHeadersFallBackToTheAlbConnection() throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET /api/v1/terms HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "X-Forwarded-For: " + FORWARDED_CHAIN + "\r\n"
                    + "CF-Connecting-IP: 203.0.113.50\r\nCF-Connecting-IP: 203.0.113.51\r\n"
                    + "Connection: close\r\n\r\n").getBytes(ISO_8859_1));
            var response = new String(socket.getInputStream().readAllBytes(), ISO_8859_1);
            assertTrue(response.startsWith("HTTP/1.1 200"), response);
        }

        assertEquals(List.of("127.0.0.1"), USER.received("/api/v1/terms").getFirst().header("Client-Ip"));
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }
}
