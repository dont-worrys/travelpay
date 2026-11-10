package com.travelpay.bank;

import com.travelpay.bank.presentation.fault.BankFaultInjectionFilter;
import com.travelpay.bank.presentation.fault.BankWithdrawalLostRateFilter;
import com.travelpay.bank.presentation.fault.BankWithdrawalLostRateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * mock-bank.fault.enabled를 지정하지 않으면(application.yml 기본 false) 장애 주입 빈이 없다:
 * Mock-Fault: LOST 헤더도, 유실 비율 1.0도 무시하고 정상 응답한다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mock-bank.fault.withdrawal-lost-rate=1.0")
class BankFaultInjectionDisabledByDefaultTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", "mock_bank");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void enabled가_없으면_Mock_Fault_LOST와_유실_비율을_무시하고_201을_준다() throws Exception {
        HttpResponse<String> headerLost = withdraw("w-disabled-header", "LOST");
        HttpResponse<String> rateLost = withdraw("w-disabled-rate", null);

        assertEquals(201, headerLost.statusCode());
        assertEquals(201, rateLost.statusCode());
        assertEquals(0, context.getBeansOfType(BankFaultInjectionFilter.class).size());
        assertEquals(0, context.getBeansOfType(BankWithdrawalLostRateFilter.class).size());
        assertEquals(0, context.getBeansOfType(BankWithdrawalLostRateProperties.class).size());
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bank_transactions WHERE request_id IN ('w-disabled-header', 'w-disabled-rate')",
                Long.class));
    }

    private HttpResponse<String> withdraw(String requestId, String fault) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/internal/v1/bank/withdrawals"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"requestId":"%s","bankCode":"004","accountNumber":"12340101234567",\
                        "holderName":"유재석","amount":1000}""".formatted(requestId)));
        if (fault != null) {
            builder.header(BankFaultInjectionFilter.HEADER, fault);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
