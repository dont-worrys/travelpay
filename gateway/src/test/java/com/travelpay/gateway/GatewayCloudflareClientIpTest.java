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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 127.0.0.1을 우리 쪽 프록시(ALB·cloudflared) 대역으로 지정해, 그 뒤에서 CF-Connecting-IP가 실제 IP로 쓰이는지 확인합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.cloud-platform=KUBERNETES",
        "eureka.client.enabled=false",
        "travelpay.gateway.client-ip.proxy-addresses=127.0.0.1/32",
        "logging.level.root=WARN"
})
class GatewayCloudflareClientIpTest {

    private static final StubServer USER = StubServer.start();

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
    @CsvSource({"203.0.113.50,203.0.113.50", "2001:db8::50,2001:db8::50", "not-an-ip,127.0.0.1"})
    void theIpCloudflareSentBecomesTheClientIp(String cloudflareHeader, String expected) {
        WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).responseTimeout(Duration.ofSeconds(10)).build()
                .get().uri("/api/v1/terms").header("CF-Connecting-IP", cloudflareHeader)
                .header("X-Forwarded-For", "203.0.113.99")
                .exchange().expectStatus().isOk();

        var arrived = USER.received("/api/v1/terms").getFirst();
        assertEquals(List.of(expected), arrived.header("Client-Ip"));
        assertTrue(arrived.header("CF-Connecting-IP").isEmpty());
    }

    @Test
    void twoCloudflareIpHeaderLinesAreNotTrusted() throws IOException {
        // 같은 헤더를 두 줄로 보내려고 소켓에 직접 씀
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET /api/v1/terms HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "CF-Connecting-IP: 203.0.113.8\r\nCF-Connecting-IP: 203.0.113.9\r\n"
                    + "Connection: close\r\n\r\n").getBytes(ISO_8859_1));
            var response = new String(socket.getInputStream().readAllBytes(), ISO_8859_1);
            assertTrue(response.startsWith("HTTP/1.1 200"), response);
        }

        assertEquals(List.of("127.0.0.1"), USER.received("/api/v1/terms").getFirst().header("Client-Ip"));
    }
}
