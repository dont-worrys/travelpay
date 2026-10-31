package com.travelpay.wallet.application.charge;

import com.travelpay.wallet.application.charge.dto.BankResult;
import com.travelpay.wallet.application.charge.dto.LedgerPostResult;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.charge.AccountNumberCipher;
import com.travelpay.wallet.domain.charge.AccountNumberCipherException;
import com.travelpay.wallet.domain.charge.BankAccount;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.infra.kafka.ChargeSagaConsumer;
import com.travelpay.wallet.infra.kafka.KafkaOutboxMessageSender;
import com.travelpay.wallet.infra.kafka.WalletKafkaTopicConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실제 Postgres(Flyway 적용)에서 충전 SAGA 실행기를 검증한다. 은행·원장 클라이언트는 테스트 대역이고,
 * Kafka 컨슈머 컨테이너는 끄고 리스너 메서드를 직접 호출한다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "travelpay.wallet.outbox.publisher.enabled=false",
        "travelpay.wallet.charge.saga.consumer.enabled=false",
        "spring.kafka.admin.auto-create=false"
})
class ChargeSagaIntegrationTest {

    private static final String SCHEMA = "wallet_service";
    private static final long AMOUNT = 10_000;
    private static final String TIMEOUT = "feign.RetryableException";

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
    private BankClient bankClient;

    @MockitoBean
    private LedgerTransactionClient ledgerClient;

    @MockitoSpyBean
    private ChargeSagaSteps sagaSteps;

    @Autowired
    private ChargeSagaExecutor executor;

    @Autowired
    private ChargeSagaConsumer consumer;

    @Autowired
    private ChargeTransactionSteps steps;

    @Autowired
    private AccountNumberCipher cipher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID chargeId;
    private UUID userId;
    private UUID walletId;
    private String bankRequestId;
    private String refundRequestId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".charge_status_history, " + SCHEMA + ".outbox_events, "
                + SCHEMA + ".charge_requests, " + SCHEMA + ".wallet_currencies, " + SCHEMA + ".wallets");
        userId = UUID.randomUUID();
        walletId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO wallets (id, user_id, status) VALUES (?, ?, 'ACTIVE')", walletId, userId);
        ChargeRequest charge = steps.accept(ChargeRequest.create(userId, walletId, AMOUNT, Currency.KRW,
                BankAccount.encrypt("004", "1234567890", cipher), "홍길동", UUID.randomUUID()));
        chargeId = charge.getId();
        bankRequestId = charge.getBankRequestId();
        refundRequestId = charge.getRefundRequestId();
    }

    @Test
    void 정상이면_COMPLETED가_되고_은행_거래와_원장_참조를_저장한다() {
        UUID bankTxId = UUID.randomUUID();
        UUID ledgerTxId = UUID.randomUUID();
        givenWithdraw(new BankResult.Succeeded(bankTxId));
        givenLedger(new LedgerPostResult.Posted(ledgerTxId));

        executor.execute(chargeId);

        Map<String, Object> row = chargeRow();
        assertEquals("COMPLETED", row.get("status"));
        assertEquals(bankTxId, row.get("bank_tx_id"));
        assertEquals(ledgerTxId, row.get("ledger_ref"));
        assertEquals(0, row.get("attempt_count"));
        assertEquals(List.of("REQUESTED", "BANK_PROCESSING", "CREDIT_PROCESSING", "COMPLETED"), historyStatuses());
        verify(bankClient).withdraw(bankRequestId, "004", "1234567890", "홍길동", AMOUNT);
        verify(ledgerClient).postCharge(chargeId, userId, walletId, AMOUNT);
    }

    @Test
    void 은행이_출금을_거절하면_FAILED와_실패_코드를_남긴다() {
        givenWithdraw(new BankResult.Failed("INSUFFICIENT_BALANCE"));

        executor.execute(chargeId);

        Map<String, Object> row = chargeRow();
        assertEquals("FAILED", row.get("status"));
        assertEquals("BANK_INSUFFICIENT_BALANCE", row.get("failure_code"));
        verify(ledgerClient, never()).postCharge(any(), any(), any(), anyLong());
    }

    @Test
    void 은행이_모르는_실패_코드로_거절하면_일반_실패_코드로_FAILED가_되고_원래_코드를_남긴다() {
        givenWithdraw(new BankResult.Failed("DAILY_LIMIT_EXCEEDED"));

        executor.execute(chargeId);

        Map<String, Object> row = chargeRow();
        assertEquals("FAILED", row.get("status"));
        assertEquals("BANK_REJECTED", row.get("failure_code"));
        assertTrue(((String) row.get("last_error")).contains("DAILY_LIMIT_EXCEEDED"));
        verify(ledgerClient, never()).postCharge(any(), any(), any(), anyLong());
    }

    @Test
    void 원장이_거절하면_환불해_REFUNDED가_된다() {
        UUID bankTxId = UUID.randomUUID();
        UUID refundTxId = UUID.randomUUID();
        givenWithdraw(new BankResult.Succeeded(bankTxId));
        givenLedger(new LedgerPostResult.Rejected("LEDGER_REJECTED_ACCOUNT_NOT_FOUND"));
        when(bankClient.refund(anyString(), any(), anyLong())).thenReturn(new BankResult.Succeeded(refundTxId));

        executor.execute(chargeId);

        Map<String, Object> row = chargeRow();
        assertEquals("REFUNDED", row.get("status"));
        assertEquals("LEDGER_REJECTED", row.get("failure_code"));
        assertEquals(bankTxId, row.get("bank_tx_id"));
        assertEquals(refundTxId, row.get("refund_bank_tx_id"));
        assertNull(row.get("ledger_ref"));
        assertEquals(List.of("REQUESTED", "BANK_PROCESSING", "CREDIT_PROCESSING", "REFUND_PROCESSING", "REFUNDED"),
                historyStatuses());
        verify(bankClient).refund(refundRequestId, bankTxId, AMOUNT);
    }

    @Test
    void 원장이_409로_응답하면_재시도하지_않고_MANUAL_REVIEW로_전이한다() {
        givenWithdraw(new BankResult.Succeeded(UUID.randomUUID()));
        givenLedger(new LedgerPostResult.Invalid("feign.FeignException$Conflict"));

        executor.execute(chargeId);

        Map<String, Object> row = chargeRow();
        assertEquals("MANUAL_REVIEW", row.get("status"));
        assertEquals("RESPONSE_INVALID", row.get("failure_code"));
        assertEquals("feign.FeignException$Conflict", row.get("last_error"));
        verify(ledgerClient, times(1)).postCharge(any(), any(), any(), anyLong());
        verify(bankClient, never()).refund(anyString(), any(), anyLong());
    }

    @Test
    void BANK_PROCESSING_건에서_계좌번호를_복호화하지_못하면_은행_호출_없이_MANUAL_REVIEW로_전이한다() {
        jdbcTemplate.update("UPDATE charge_requests SET status = 'BANK_PROCESSING', account_number_enc = ? "
                + "WHERE id = ?", "v1:@@invalid", chargeId);

        executor.execute(chargeId);

        Map<String, Object> row = chargeRow();
        assertEquals("MANUAL_REVIEW", row.get("status"));
        assertEquals("ACCOUNT_DECRYPT_FAILED", row.get("failure_code"));
        assertEquals(AccountNumberCipherException.class.getName(), row.get("last_error"));
        assertEquals(0, row.get("attempt_count"));
        verify(bankClient, never()).withdraw(anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(ledgerClient, never()).postCharge(any(), any(), any(), anyLong());

        executor.execute(chargeId);
        assertEquals("MANUAL_REVIEW", chargeRow().get("status"));
        verify(bankClient, never()).withdraw(anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void 은행_결과를_모르면_BANK_PROCESSING에_머물고_원장과_환불을_호출하지_않는다() {
        givenWithdraw(new BankResult.Unknown(TIMEOUT));

        executor.execute(chargeId);

        Map<String, Object> row = chargeRow();
        assertEquals("BANK_PROCESSING", row.get("status"));
        assertEquals(1, row.get("attempt_count"));
        assertEquals(TIMEOUT, row.get("last_error"));
        verify(ledgerClient, never()).postCharge(any(), any(), any(), anyLong());
        verify(bankClient, never()).refund(anyString(), any(), anyLong());
    }

    @Test
    void 원장_결과를_모르면_CREDIT_PROCESSING에_머물고_환불을_호출하지_않는다() {
        UUID bankTxId = UUID.randomUUID();
        givenWithdraw(new BankResult.Succeeded(bankTxId));
        givenLedger(new LedgerPostResult.Unknown(TIMEOUT));

        executor.execute(chargeId);

        Map<String, Object> row = chargeRow();
        assertEquals("CREDIT_PROCESSING", row.get("status"));
        assertEquals(bankTxId, row.get("bank_tx_id"));
        assertEquals(1, row.get("attempt_count"));
        verify(bankClient, never()).refund(anyString(), any(), anyLong());
    }

    @Test
    void 결과_모름이_5회가_되면_MANUAL_REVIEW로_전이한다() {
        givenWithdraw(new BankResult.Unknown(TIMEOUT));

        for (int i = 0; i < 4; i++) {
            executor.execute(chargeId);
        }
        assertEquals("BANK_PROCESSING", chargeRow().get("status"));
        assertEquals(4, chargeRow().get("attempt_count"));

        executor.execute(chargeId);

        Map<String, Object> row = chargeRow();
        assertEquals("MANUAL_REVIEW", row.get("status"));
        assertEquals("RETRY_EXHAUSTED", row.get("failure_code"));
        assertEquals(5, row.get("attempt_count"));
        verify(bankClient, times(5)).withdraw(eq(bankRequestId), anyString(), anyString(), anyString(), anyLong());

        executor.execute(chargeId);
        verify(bankClient, times(5)).withdraw(anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void REQUESTED_건을_동시에_실행해도_은행과_원장은_한_번씩만_호출한다() throws Exception {
        givenWithdraw(new BankResult.Succeeded(UUID.randomUUID()));
        givenLedger(new LedgerPostResult.Posted(UUID.randomUUID()));
        holdFirstLoadsUntilBothRead();

        runConcurrently(2);

        assertEquals("COMPLETED", chargeRow().get("status"));
        verify(bankClient, times(1)).withdraw(anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(ledgerClient, times(1)).postCharge(any(), any(), any(), anyLong());
    }

    @Test
    void BANK_PROCESSING_건을_동시에_실행하면_은행은_같은_요청_ID로_최대_2회_원장은_1회_호출한다() throws Exception {
        jdbcTemplate.update("UPDATE charge_requests SET status = 'BANK_PROCESSING' WHERE id = ?", chargeId);
        givenWithdraw(new BankResult.Succeeded(UUID.randomUUID()));
        givenLedger(new LedgerPostResult.Posted(UUID.randomUUID()));
        holdFirstLoadsUntilBothRead();

        runConcurrently(2);

        assertEquals("COMPLETED", chargeRow().get("status"));
        verify(bankClient, atMost(2)).withdraw(anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(bankClient, never()).withdraw(
                org.mockito.ArgumentMatchers.argThat(id -> !bankRequestId.equals(id)),
                anyString(), anyString(), anyString(), anyLong());
        verify(ledgerClient, times(1)).postCharge(any(), any(), any(), anyLong());
    }

    @Test
    void 같은_이벤트를_두_번_받으면_두_번째는_아무것도_하지_않는다() {
        givenWithdraw(new BankResult.Succeeded(UUID.randomUUID()));
        givenLedger(new LedgerPostResult.Posted(UUID.randomUUID()));
        String eventId = UUID.randomUUID().toString();
        ConsumerRecord<String, String> record = chargeEvent(eventId);

        consumer.onChargeRequested(record);
        Map<String, Object> afterFirst = chargeRow();
        int historyAfterFirst = historyStatuses().size();

        consumer.onChargeRequested(record);

        assertEquals("COMPLETED", chargeRow().get("status"));
        assertEquals(afterFirst.get("updated_at"), chargeRow().get("updated_at"));
        assertEquals(historyAfterFirst, historyStatuses().size());
        verify(bankClient, times(1)).withdraw(anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(ledgerClient, times(1)).postCharge(any(), any(), any(), anyLong());
        Integer traced = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM charge_status_history WHERE charge_id = ? AND trace_id = ?",
                Integer.class, chargeId, eventId);
        assertEquals(3, traced);
    }

    private void givenWithdraw(BankResult result) {
        when(bankClient.withdraw(anyString(), anyString(), anyString(), anyString(), anyLong())).thenReturn(result);
    }

    private void givenLedger(LedgerPostResult result) {
        when(ledgerClient.postCharge(any(), any(), any(), anyLong())).thenReturn(result);
    }

    /** 두 실행기가 모두 같은 상태를 읽은 뒤에 진행하도록, 처음 두 번의 조회는 서로를 기다린다 */
    private void holdFirstLoadsUntilBothRead() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger loads = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (loads.getAndIncrement() < 2) {
                barrier.await(5, TimeUnit.SECONDS);
            }
            return result;
        }).when(sagaSteps).load(any());
    }

    private void runConcurrently(int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> executor.execute(chargeId)));
            }
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private ConsumerRecord<String, String> chargeEvent(String eventId) {
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE aggregate_id = ?", String.class, chargeId);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(WalletKafkaTopicConfig.CHARGE_REQUESTED, 0, 0L,
                chargeId.toString(), payload);
        record.headers().add(KafkaOutboxMessageSender.EVENT_ID_HEADER, eventId.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private Map<String, Object> chargeRow() {
        return jdbcTemplate.queryForMap("""
                SELECT status, failure_code, last_error, attempt_count, bank_tx_id, ledger_ref, refund_bank_tx_id,
                       updated_at
                  FROM charge_requests WHERE id = ?
                """, chargeId);
    }

    private List<String> historyStatuses() {
        return jdbcTemplate.queryForList(
                "SELECT to_status FROM charge_status_history WHERE charge_id = ? ORDER BY created_at, id",
                String.class, chargeId);
    }
}
