package com.travelpay.wallet.presentation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Base64;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 애플리케이션 구성으로 지갑·충전 API 문서 그룹을 생성해 확인한다. 컨텍스트 기동에 DB가 필요하며 Docker가 없으면 건너뛴다 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "travelpay.wallet.outbox.publisher.enabled=false",
        "travelpay.wallet.charge.recovery.enabled=false",
        "travelpay.wallet.charge.reconciliation.enabled=false",
        "travelpay.wallet.charge.saga.consumer.enabled=false",
        "spring.kafka.admin.auto-create=false"
})
class WalletApiDocsIntegrationTest {

    private static final String WALLET_DOCS = "/v3/api-docs/wallet";
    private static final String ADMIN_DOCS = "/v3/api-docs/charge-admin";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", "wallet_service");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("travelpay.wallet.account-number-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void documentsOnlyMemberWalletAndChargeEndpoints() throws Exception {
        mvc.perform(get(WALLET_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("TravelPay 지갑·충전 API"))
                .andExpect(jsonPath("$.paths.keys()", containsInAnyOrder(
                        "/api/v1/wallets",
                        "/api/v1/wallets/me",
                        "/api/v1/wallets/me/balances",
                        "/api/v1/charges",
                        "/api/v1/charges/{chargeId}")));
    }

    @Test
    void documentsOnlyAdminChargeEndpoints() throws Exception {
        mvc.perform(get(ADMIN_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("TravelPay 충전 관리 API"))
                .andExpect(jsonPath("$.info.description").value(containsString("MASTER")))
                .andExpect(jsonPath("$.paths.keys()", containsInAnyOrder(
                        "/api/v1/admin/charges",
                        "/api/v1/admin/charges/{chargeId}/review",
                        "/api/v1/admin/charges/{chargeId}/resolve",
                        "/api/v1/admin/charges/reconciliations",
                        "/api/v1/admin/charges/reconciliations/{runId}")));
    }

    @Test
    void doesNotDocumentInternalEndpoints() throws Exception {
        mvc.perform(get(WALLET_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths.keys()", everyItem(not(startsWith("/internal")))));
        mvc.perform(get(ADMIN_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths.keys()", everyItem(not(startsWith("/internal")))));
    }

    @Test
    void documentsHeadersWithoutExposingCurrentUserParameter() throws Exception {
        mvc.perform(get(WALLET_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/charges'].post.parameters[*].name",
                        containsInAnyOrder("Idempotency-Key", "User-Id", "User-Role")))
                .andExpect(jsonPath("$.paths['/api/v1/charges'].post.parameters[?(@.name == 'Idempotency-Key')].required",
                        hasItem(true)))
                .andExpect(jsonPath("$.paths['/api/v1/wallets'].post.parameters[*].name",
                        containsInAnyOrder("User-Id", "User-Role")))
                .andExpect(jsonPath("$.paths.*.*.parameters[?(@.name == 'user')]", empty()));
        mvc.perform(get(ADMIN_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/admin/charges/{chargeId}/resolve'].post.parameters[*].name",
                        containsInAnyOrder("chargeId", "Idempotency-Key", "User-Id", "User-Role")))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/admin/charges/{chargeId}/resolve'].post.parameters[?(@.name == 'Idempotency-Key')].required",
                        hasItem(true)))
                .andExpect(jsonPath("$.paths.*.*.parameters[?(@.name == 'User-Role')].example", everyItem(
                        is("MASTER"))))
                .andExpect(jsonPath("$.paths.*.*.parameters[?(@.name == 'user')]", empty()));
    }
}
