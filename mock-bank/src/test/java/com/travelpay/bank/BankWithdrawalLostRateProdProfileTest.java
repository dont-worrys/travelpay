package com.travelpay.bank;

import com.travelpay.bank.presentation.fault.BankWithdrawalLostRateFilter;
import com.travelpay.bank.presentation.fault.BankWithdrawalLostRateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
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
 * prod 프로필에서는 유실 비율을 읽지도 검증하지도 않는다: 범위 밖 값(5)을 줘도 기동하고 출금은 정상 응답이다.
 * 배포와 같게 application-prod.yml만 읽는다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=application-prod", "mock-bank.fault.withdrawal-lost-rate=5"})
class BankWithdrawalLostRateProdProfileTest {

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

    @Test
    void prod에서는_유실_비율을_지정해도_정상_응답을_준다() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/internal/v1/bank/withdrawals"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"requestId":"w-prod-rate","bankCode":"004","accountNumber":"12340101234567",\
                        "holderName":"유재석","amount":1000}"""))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(201, response.statusCode());
        assertEquals(0, context.getBeansOfType(BankWithdrawalLostRateFilter.class).size());
        assertEquals(0, context.getBeansOfType(BankWithdrawalLostRateProperties.class).size());
    }
}
