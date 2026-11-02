package com.travelpay.wallet.application.charge;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.charge.AccountNumberCipher;
import com.travelpay.wallet.domain.charge.BankAccount;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.infra.client.bank.BankFeignClient;
import com.travelpay.wallet.infra.client.bank.BankRefundRequest;
import com.travelpay.wallet.infra.client.bank.BankTransactionResponse;
import com.travelpay.wallet.infra.client.ledger.LedgerTransactionFeignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static com.travelpay.wallet.infra.client.FeignFailures.error;
import static com.travelpay.wallet.infra.client.FeignFailures.timeout;
import static com.travelpay.wallet.infra.client.FeignFailures.undecodable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Feign 실패가 실제 어댑터를 거쳐 충전 SAGA 결과(환불·MANUAL_REVIEW·결과 모름)로 이어지는지 검증한다.
 * ChargeSagaIntegrationTest와 같은 설정이고, 테스트 대역은 어댑터가 아니라 Feign 클라이언트다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "travelpay.wallet.outbox.publisher.enabled=false",
        "travelpay.wallet.charge.recovery.enabled=false",
        "travelpay.wallet.charge.saga.consumer.enabled=false",
        "spring.kafka.admin.auto-create=false"
})
class ChargeSagaFeignIntegrationTest {

    private static final String SCHEMA = "wallet_service";
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

    @MockitoBean
    private BankFeignClient bankFeignClient;

    @MockitoBean
    private LedgerTransactionFeignClient ledgerFeignClient;

    @Autowired
    private ChargeSagaExecutor executor;

    @Autowired
    private ChargeTransactionSteps steps;

    @Autowired
    private AccountNumberCipher cipher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID chargeId;
    private String bankRequestId;
    private String refundRequestId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".charge_status_history, " + SCHEMA + ".outbox_events, "
                + SCHEMA + ".charge_requests, " + SCHEMA + ".wallet_currencies, " + SCHEMA + ".wallets");
        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO wallets (id, user_id, status) VALUES (?, ?, 'ACTIVE')", walletId, userId);
        ChargeRequest charge = steps.accept(ChargeRequest.create(userId, walletId, AMOUNT, Currency.KRW,
                BankAccount.encrypt("004", "1234567890", cipher), "홍길동", UUID.randomUUID()));
        chargeId = charge.getId();
        bankRequestId = charge.getBankRequestId();
        refundRequestId = charge.getRefundRequestId();
    }

    @Test
    void 원장이_LEDGER_REJECTED_코드로_422를_주면_환불해_REFUNDED가_된다() {
        UUID bankTxId = givenWithdrawSucceeded();
        UUID refundTxId = UUID.randomUUID();
        when(ledgerFeignClient.register(any())).thenThrow(error(422, "LEDGER_REJECTED_ACCOUNT_NOT_FOUND"));
        when(bankFeignClient.refund(any())).thenReturn(ApiResponse.success(
                new BankTransactionResponse(refundTxId, refundRequestId, "REFUND", "SUCCEEDED", null)));

        executor.execute(chargeId);

        Map<String, Object> row = chargeRow();
        assertEquals("REFUNDED", row.get("status"));
        assertEquals("LEDGER_REJECTED", row.get("failure_code"));
        assertEquals(refundTxId, row.get("refund_bank_tx_id"));
        verify(bankFeignClient).refund(new BankRefundRequest(refundRequestId, bankTxId, AMOUNT));
    }

    @Test
    void 원장이_409를_주면_환불하지_않고_MANUAL_REVIEW가_된다() {
        givenWithdrawSucceeded();
        when(ledgerFeignClient.register(any())).thenThrow(error(409, "LEDGER_DUPLICATE_REFERENCE"));

        executor.execute(chargeId);

        Map<String, Object> row = chargeRow();
        assertEquals("MANUAL_REVIEW", row.get("status"));
        assertEquals("RESPONSE_INVALID", row.get("failure_code"));
        verify(bankFeignClient, never()).refund(any());
    }

    @Test
    void 은행_2xx_본문을_해석하지_못하면_BANK_PROCESSING에_머물고_보상하지_않는다() {
        when(bankFeignClient.withdraw(any())).thenThrow(undecodable());

        executor.execute(chargeId);

        Map<String, Object> row = chargeRow();
        assertEquals("BANK_PROCESSING", row.get("status"));
        assertEquals(1, row.get("attempt_count"));
        verify(ledgerFeignClient, never()).register(any());
        verify(bankFeignClient, never()).refund(any());
    }

    @Test
    void 원장_시간_초과면_CREDIT_PROCESSING에_머물고_환불하지_않는다() {
        UUID bankTxId = givenWithdrawSucceeded();
        when(ledgerFeignClient.register(any())).thenThrow(timeout());

        executor.execute(chargeId);

        Map<String, Object> row = chargeRow();
        assertEquals("CREDIT_PROCESSING", row.get("status"));
        assertEquals(bankTxId, row.get("bank_tx_id"));
        assertEquals(1, row.get("attempt_count"));
        verify(bankFeignClient, never()).refund(any());
    }

    private UUID givenWithdrawSucceeded() {
        UUID bankTxId = UUID.randomUUID();
        when(bankFeignClient.withdraw(any())).thenReturn(ApiResponse.success(
                new BankTransactionResponse(bankTxId, bankRequestId, "DEBIT", "SUCCEEDED", null)));
        return bankTxId;
    }

    private Map<String, Object> chargeRow() {
        return jdbcTemplate.queryForMap("""
                SELECT status, failure_code, attempt_count, bank_tx_id, refund_bank_tx_id
                  FROM charge_requests WHERE id = ?
                """, chargeId);
    }
}
