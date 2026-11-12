package com.travelpay.gateway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 실제 설정 파일의 라우트로 Gateway를 띄우고, 연결을 받지 않는 포트를 user-service 주소로 둬서
 * Eureka 목록에 남은 종료된 인스턴스로 요청이 갔을 때의 응답을 확인합니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "logging.level.root=WARN"
})
class GatewayUpstreamConnectionFailureTest {

    // 포트를 묶어 두기만 하고 연결을 받지 않으므로 접속은 거부되고, 테스트 중 다른 프로세스가 같은 포트를 차지하지 못합니다.
    private static final Socket CLOSED_SERVICE = boundWithoutListening();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void userServiceOnAClosedPort(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri",
                () -> "http://127.0.0.1:" + CLOSED_SERVICE.getLocalPort());
    }

    @AfterAll
    static void releasePort() throws IOException {
        CLOSED_SERVICE.close();
    }

    @Test
    void aRequestThatCannotReachTheServiceIsA503() {
        WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build()
                .get().uri("/api/v1/terms")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error.code").isEqualTo("COMMON_SERVICE_UNAVAILABLE");
    }

    private static Socket boundWithoutListening() {
        var socket = new Socket();
        try {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket;
        } catch (IOException exception) {
            throw new UncheckedIOException("테스트용 로컬 포트를 묶지 못했습니다.", exception);
        }
    }
}
