package com.travelpay.bank;

import com.travelpay.bank.presentation.fault.BankFaultInjectionFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
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
 * prod 프로필에서는 Mock-Fault 헤더가 와도 장애 주입이 동작하지 않는다.
 * 배포와 같게 application-prod.yml만 읽고(SPRING_CONFIG_NAME) prod 프로필을 켠다(SPRING_PROFILES_ACTIVE).
 * Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=application-prod")
class BankFaultInjectionProdProfileTest {

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
    void prod에서는_Mock_Fault_LOST를_무시하고_원래_응답을_준다() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/internal/v1/bank/withdrawals"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header(BankFaultInjectionFilter.HEADER, "LOST")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"requestId":"w-prod-lost","bankCode":"004","accountNumber":"12340101234567",\
                        "holderName":"유재석","amount":1000}"""))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(201, response.statusCode());
        assertEquals(0, context.getBeansOfType(BankFaultInjectionFilter.class).size());
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bank_transactions WHERE request_id = 'w-prod-lost'", Long.class));
    }
}
