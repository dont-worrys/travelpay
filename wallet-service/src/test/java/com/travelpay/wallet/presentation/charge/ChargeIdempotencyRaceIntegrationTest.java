package com.travelpay.wallet.presentation.charge;

import com.travelpay.wallet.application.charge.ChargeTransactionSteps;
import com.travelpay.wallet.application.charge.UserFundingIdentityClient;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.charge.AccountNumberCipher;
import com.travelpay.wallet.domain.charge.BankAccount;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.DuplicateChargeRequestException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JDBC batch(hibernate.jdbc.batch_size)를 켠 상태에서 충전 멱등 키 UNIQUE 위반이 기존 catch로 잡히는지 검증한다.
 * 같은 키의 충전이 먼저 커밋된 뒤, 처음 조회만 비어 있었던 것처럼 만들어 경쟁에서 진 요청을 재현한다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "travelpay.wallet.outbox.publisher.enabled=false",
        "travelpay.wallet.charge.recovery.enabled=false",
        "travelpay.wallet.charge.reconciliation.enabled=false",
        "travelpay.wallet.charge.saga.consumer.enabled=false",
        "spring.kafka.admin.auto-create=false"
})
class ChargeIdempotencyRaceIntegrationTest {

    private static final String SCHEMA = "wallet_service";
    private static final String IDEMPOTENCY_CONSTRAINT = "uk_charge_requests_idempotency";
    private static final String BANK_CODE = "004";
    private static final String ACCOUNT_NUMBER = "1234567890";
    private static final String REAL_NAME = "홍길동";
    private static final long AMOUNT = 10_000;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

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

    @MockitoSpyBean
    private ChargeTransactionSteps steps;

    @MockitoBean
    private UserFundingIdentityClient userFundingIdentityClient;

    @Autowired
    private AccountNumberCipher cipher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    private MockMvc mockMvc;
    private UUID userId;
    private UUID walletId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".charge_status_history, " + SCHEMA + ".outbox_events, "
                + SCHEMA + ".charge_requests, " + SCHEMA + ".wallet_currencies, " + SCHEMA + ".wallets CASCADE");
        userId = UUID.randomUUID();
        walletId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO wallets (id, user_id, status) VALUES (?, ?, 'ACTIVE')", walletId, userId);
        when(userFundingIdentityClient.findRealName(userId)).thenReturn(REAL_NAME);
    }

    @Test
    void 같은_키가_먼저_커밋된_뒤_저장하면_UNIQUE_위반을_잡아_기존_충전을_돌려준다() throws Exception {
        assertEquals("50", environment.getProperty("spring.jpa.properties.hibernate.jdbc.batch_size"));
        UUID idempotencyKey = UUID.randomUUID();
        // 경쟁에서 이긴 요청: 같은 키로 먼저 커밋
        ChargeRequest winner = steps.accept(ChargeRequest.create(userId, walletId, AMOUNT, Currency.KRW,
                BankAccount.encrypt(BANK_CODE, ACCOUNT_NUMBER, cipher), REAL_NAME, idempotencyKey));
        clearInvocations(steps);

        // 진 요청: 첫 조회는 이긴 요청 커밋 전에 한 것처럼 비어 있고, catch 뒤 재조회는 실제로 읽는다
        doReturn(Optional.empty()).doCallRealMethod()
                .when(steps).findByIdempotencyKey(userId, idempotencyKey);
        AtomicReference<DuplicateChargeRequestException> caught = new AtomicReference<>();
        doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } catch (DuplicateChargeRequestException e) {
                caught.set(e);
                throw e;
            }
        }).when(steps).accept(any());

        mockMvc.perform(post("/api/v1/charges")
                        .header("User-Id", userId.toString())
                        .header("User-Role", "USER")
                        .header(ChargeController.IDEMPOTENCY_KEY_HEADER, idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": %d, "currency": "KRW", "bankCode": "%s", "accountNumber": "%s"}
                                """.formatted(AMOUNT, BANK_CODE, ACCOUNT_NUMBER)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.chargeId").value(winner.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));

        // catch 경로: 저장 1번이 멱등 키 UNIQUE 위반으로 실패하고, 재조회 1번
        verify(steps, times(1)).accept(any());
        verify(steps, times(2)).findByIdempotencyKey(userId, idempotencyKey);
        assertNotNull(caught.get(), "DuplicateChargeRequestException이 나지 않았습니다.");
        assertEquals(IDEMPOTENCY_CONSTRAINT, constraintName(caught.get()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM charge_requests WHERE user_id = ? AND idempotency_key = ?",
                Integer.class, userId, idempotencyKey));
        // 진 요청의 이력·Outbox도 함께 롤백되어 이긴 요청 것만 남는다
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM charge_status_history", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Integer.class));
    }

    private static String constraintName(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }
}
