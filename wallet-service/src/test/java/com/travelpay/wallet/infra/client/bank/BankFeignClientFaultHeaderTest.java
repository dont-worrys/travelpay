package com.travelpay.wallet.infra.client.bank;

import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 실제 HTTP 요청에서 Mock-Fault 헤더가 출금 인자에 따라서만 실리는지 확인 */
class BankFeignClientFaultHeaderTest {

    private final Map<String, List<String>> received = new ConcurrentHashMap<>();
    private HttpServer server;
    private BankFeignClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            List<String> values = exchange.getRequestHeaders().get(BankFaultInjection.HEADER);
            received.put(exchange.getRequestURI().getPath(), values == null ? List.of() : values);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        client = Feign.builder()
                .contract(new SpringMvcContract())
                .encoder((body, type, template) -> template.body("{}"))
                .decoder((response, type) -> null)
                .target(BankFeignClient.class, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 출금_인자가_null이면_헤더를_보내지_않음() {
        client.withdraw(null, new BankWithdrawalRequest("r-1", "004", "1234567890", "홍길동", 10_000));

        assertEquals(List.of(), received.get("/withdrawals"));
    }

    @Test
    void 출금_인자가_있으면_헤더를_보냄() {
        client.withdraw("LOST", new BankWithdrawalRequest("r-1", "004", "1234567890", "홍길동", 10_000));

        assertEquals(List.of("LOST"), received.get("/withdrawals"));
    }

    @Test
    void 환불은_헤더를_보내지_않음() {
        client.refund(new BankRefundRequest("r-2", UUID.randomUUID(), 10_000));

        assertFalse(received.get("/refunds").contains("LOST"));
        assertEquals(List.of(), received.get("/refunds"));
    }
}
