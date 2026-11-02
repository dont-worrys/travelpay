package com.travelpay.gateway;

import java.time.Duration;
import java.util.List;

import com.travelpay.gateway.support.StubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 클라우드 환경에서도 외부 전달 헤더가 실제 연결 주소와 프록시 신뢰 판단을 바꾸지 못하게 합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.cloud-platform=KUBERNETES",
        "eureka.client.enabled=false",
        "travelpay.gateway.client-ip.proxy-addresses=",
        "logging.level.root=WARN"
})
class GatewayCloudPlatformClientIpTest {

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
    @CsvSource({
            "X-Forwarded-For,203.0.113.99",
            "Forwarded,for=203.0.113.99",
            "X-Forwarded-For,173.245.48.2",
            "Forwarded,for=173.245.48.2"
    })
    void externalForwardedHeadersCannotReplaceThePeerOrMakeItATrustedProxy(String name, String value) {
        WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build()
                .get().uri("/api/v1/terms")
                .header(name, value)
                .header("Client-Ip", "203.0.113.98")
                .header("CF-Connecting-IP", "203.0.113.50")
                .exchange().expectStatus().isOk();

        var arrived = USER.received("/api/v1/terms").getFirst();
        assertEquals(List.of("127.0.0.1"), arrived.header("Client-Ip"));
        assertTrue(arrived.header("CF-Connecting-IP").isEmpty());
    }
}
