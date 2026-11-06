package com.travelpay.wallet.presentation.charge;

import com.travelpay.common.error.BusinessException;
import com.travelpay.wallet.application.charge.BankClient;
import com.travelpay.wallet.application.charge.ChargeRecoveryScheduler;
import com.travelpay.wallet.application.charge.ChargeRecoverySteps;
import com.travelpay.wallet.application.charge.ChargeSagaExecutor;
import com.travelpay.wallet.application.charge.ChargeTransactionSteps;
import com.travelpay.wallet.application.charge.LedgerTransactionClient;
import com.travelpay.wallet.application.charge.dto.BankResult;
import com.travelpay.wallet.application.charge.dto.LedgerPostResult;
import com.travelpay.wallet.application.reconciliation.BankTransactionQueryClient;
import com.travelpay.wallet.application.reconciliation.LedgerTransactionQueryClient;
import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import com.travelpay.wallet.application.resolution.ChargeResolutionService;
import com.travelpay.wallet.application.resolution.dto.ChargeResolutionResult;
import com.travelpay.wallet.application.resolution.dto.ResolveChargeCommand;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.charge.AccountNumberCipher;
import com.travelpay.wallet.domain.charge.BankAccount;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import com.travelpay.wallet.domain.resolution.ResolutionAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 충전 보정 API를 실제 Postgres(Flyway 적용)에서 검증한다. 은행·원장 단건 조회 포트와 SAGA 외부 클라이언트는 대역이다.
 * Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "travelpay.wallet.outbox.publisher.enabled=false",
        "travelpay.wallet.charge.recovery.enabled=false",
        "travelpay.wallet.charge.reconciliation.enabled=false",
        "travelpay.wallet.charge.saga.consumer.enabled=false",
        "spring.kafka.admin.auto-create=false",
        "travelpay.wallet.charge.resolution.not-found-confirm-after=10m"
})
class ChargeResolutionIntegrationTest {

    private static final String SCHEMA = "wallet_service";
    private static final String PATH = "/api/v1/admin/charges/";
    private static final long AMOUNT = 10_000;
    private static final UUID OPERATOR = UUID.randomUUID();
    /** 확정 시간(10분)이 지난 전환 */
    private static final int CONFIRMED_MINUTES_AGO = 11;
    /** 확정 시간 전 전환 */
    private static final int RECENT_MINUTES_AGO = 1;

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
    private BankTransactionQueryClient bankQuery;

    @MockitoBean
    private LedgerTransactionQueryClient ledgerQuery;

    @MockitoBean
    private BankClient bankClient;

    @MockitoBean
    private LedgerTransactionClient ledgerClient;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChargeTransactionSteps chargeSteps;

    @Autowired
    private ChargeResolutionService resolutionService;

    @Autowired
    private ChargeRecoverySteps recoverySteps;

    @Autowired
    private ChargeSagaExecutor executor;

    @Autowired
    private AccountNumberCipher cipher;

    private MockMvc mockMvc;
    private UUID userId;
    private UUID walletId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".charge_status_history, " + SCHEMA + ".reconciliation_results, "
                + SCHEMA + ".charge_resolutions, " + SCHEMA + ".reconciliation_runs, " + SCHEMA + ".outbox_events, "
                + SCHEMA + ".charge_requests, " + SCHEMA + ".wallet_currencies, " + SCHEMA + ".wallets CASCADE");
        userId = UUID.randomUUID();
        walletId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO wallets (id, user_id, status) VALUES (?, ?, 'ACTIVE')", walletId, userId);
    }

    // ---------------------------------------------------------------- 조치 5개

    @Test
    void CONFIRM_COMPLETED_출금_성공_원장_POSTED_환불_없음이면_COMPLETED() throws Exception {
        UUID chargeId = manualReview(RECENT_MINUTES_AGO);
        UUID bankTxId = UUID.randomUUID();
        UUID ledgerTxId = UUID.randomUUID();
        stub(chargeId, debitSucceeded(chargeId, bankTxId, AMOUNT), notFound(), posted(chargeId, ledgerTxId, AMOUNT));

        resolve(chargeId, UUID.randomUUID(), ResolutionAction.CONFIRM_COMPLETED, "원장 적립 확인", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.withdrawal.transactionId").value(bankTxId.toString()))
                .andExpect(jsonPath("$.data.ledger.status").value("POSTED"))
                .andExpect(jsonPath("$.data.refund.lookup").value("NOT_FOUND"));

        Map<String, Object> row = chargeRow(chargeId);
        assertEquals("COMPLETED", row.get("status"));
        assertEquals(bankTxId, row.get("bank_tx_id"));
        assertEquals(ledgerTxId, row.get("ledger_ref"));
        // 확정은 attempt를 그대로 두고, MANUAL_REVIEW 진입 원인(RETRY_EXHAUSTED)은 failure_code에 남기지 않는다
        assertEquals(5, row.get("attempt_count"));
        assertNull(row.get("failure_code"));
        assertResolved(chargeId, ResolutionAction.CONFIRM_COMPLETED, "COMPLETED");
        // 진입 원인은 상태 이력에 남아 있다
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM charge_status_history "
                + "WHERE charge_id = ? AND to_status = 'MANUAL_REVIEW' AND reason_code = 'RETRY_EXHAUSTED'",
                Integer.class, chargeId));
        Map<String, Object> evidence = jdbcTemplate.queryForMap(
                "SELECT * FROM charge_resolutions WHERE charge_id = ?", chargeId);
        assertEquals("FOUND", evidence.get("withdrawal_lookup"));
        assertEquals(bankTxId, evidence.get("withdrawal_tx_id"));
        assertEquals("SUCCEEDED", evidence.get("withdrawal_status"));
        assertEquals(AMOUNT, evidence.get("withdrawal_amount"));
        assertEquals("NOT_FOUND", evidence.get("refund_lookup"));
        assertEquals(ledgerTxId, evidence.get("ledger_tx_id"));
        assertEquals(AMOUNT, evidence.get("ledger_amount"));
        assertEquals(OPERATOR, evidence.get("operator_id"));
    }

    @Test
    void CONFIRM_FAILED_출금_FAILED_원장_없음이면_FAILED() throws Exception {
        UUID chargeId = manualReview(RECENT_MINUTES_AGO);
        stub(chargeId, debitFailed(chargeId, "INSUFFICIENT_BALANCE"), notFound(), notFound());

        resolve(chargeId, UUID.randomUUID(), ResolutionAction.CONFIRM_FAILED, "은행 출금 실패 확인", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultStatus").value("FAILED"));

        assertEquals("FAILED", chargeRow(chargeId).get("status"));
        assertResolved(chargeId, ResolutionAction.CONFIRM_FAILED, "FAILED");
    }

    @Test
    void CONFIRM_COMPLETED_후_회원_충전_조회에_실패_코드가_없다() throws Exception {
        UUID chargeId = manualReview(RECENT_MINUTES_AGO);
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT), notFound(),
                posted(chargeId, UUID.randomUUID(), AMOUNT));
        resolve(chargeId, UUID.randomUUID(), ResolutionAction.CONFIRM_COMPLETED, "적립 확인", null)
                .andExpect(status().isOk());

        asOwner(get("/api/v1/charges/" + chargeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.failureCode").doesNotExist())
                .andExpect(jsonPath("$.data.failureMessage").doesNotExist());
    }

    @Test
    void CONFIRM_FAILED_출금_NotFound면_BANK_NOT_PROCESSED() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        stub(chargeId, notFound(), notFound(), notFound());

        resolve(chargeId, UUID.randomUUID(), ResolutionAction.CONFIRM_FAILED, "출금 없음 확인", null)
                .andExpect(status().isOk());

        assertEquals("BANK_NOT_PROCESSED", chargeRow(chargeId).get("failure_code"));
        asOwner(get("/api/v1/charges/" + chargeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureCode").value("BANK_NOT_PROCESSED"))
                .andExpect(jsonPath("$.data.failureMessage")
                        .value("은행 출금이 이루어지지 않아 충전이 완료되지 않았습니다. 출금된 금액은 없습니다."));
    }

    @Test
    void CONFIRM_FAILED_출금_FAILED면_은행_실패_코드를_SAGA와_같은_매핑으로() throws Exception {
        Map<String, String> expected = Map.of(
                "INSUFFICIENT_BALANCE", "BANK_INSUFFICIENT_BALANCE",
                "ACCOUNT_SUSPENDED", "BANK_ACCOUNT_SUSPENDED",
                "ACCOUNT_NOT_FOUND", "BANK_ACCOUNT_NOT_FOUND",
                "HOLDER_MISMATCH", "BANK_HOLDER_MISMATCH",
                "SOMETHING_NEW", "BANK_REJECTED");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            UUID chargeId = manualReview(RECENT_MINUTES_AGO);
            stub(chargeId, debitFailed(chargeId, entry.getKey()), notFound(), notFound());

            resolve(chargeId, UUID.randomUUID(), ResolutionAction.CONFIRM_FAILED, "출금 실패 확인", null)
                    .andExpect(status().isOk());

            assertEquals(entry.getValue(), chargeRow(chargeId).get("failure_code"), entry.getKey());
            asOwner(get("/api/v1/charges/" + chargeId))
                    .andExpect(jsonPath("$.data.failureCode").value(entry.getValue()));
        }
        // 은행이 실패 코드를 주지 않아도 BANK_REJECTED
        UUID chargeId = manualReview(RECENT_MINUTES_AGO);
        stub(chargeId, debitFailed(chargeId, null), notFound(), notFound());
        resolve(chargeId, UUID.randomUUID(), ResolutionAction.CONFIRM_FAILED, "출금 실패 확인", null)
                .andExpect(status().isOk());
        assertEquals("BANK_REJECTED", chargeRow(chargeId).get("failure_code"));
    }

    @Test
    void 확정된_충전은_복구_대상_조회에_나오지_않는다() throws Exception {
        UUID completed = manualReview(RECENT_MINUTES_AGO);
        stub(completed, debitSucceeded(completed, UUID.randomUUID(), AMOUNT), notFound(),
                posted(completed, UUID.randomUUID(), AMOUNT));
        resolve(completed, UUID.randomUUID(), ResolutionAction.CONFIRM_COMPLETED, "적립 확인", null)
                .andExpect(status().isOk());
        UUID failed = manualReview(RECENT_MINUTES_AGO);
        stub(failed, debitFailed(failed, "INSUFFICIENT_BALANCE"), notFound(), notFound());
        resolve(failed, UUID.randomUUID(), ResolutionAction.CONFIRM_FAILED, "출금 실패 확인", null)
                .andExpect(status().isOk());
        UUID refunded = manualReview(RECENT_MINUTES_AGO);
        stub(refunded, debitSucceeded(refunded, UUID.randomUUID(), AMOUNT),
                refundSucceeded(refunded, UUID.randomUUID(), AMOUNT), rejected(refunded));
        resolve(refunded, UUID.randomUUID(), ResolutionAction.CONFIRM_REFUNDED, "환불 확인", null)
                .andExpect(status().isOk());
        // 대기 시간이 충분히 지난 것으로 둔다
        jdbcTemplate.update("UPDATE charge_requests SET updated_at = now() - interval '1 day'");

        assertTrue(recoverySteps.findTargets(10).isEmpty());
    }

    @Test
    void CONFIRM_REFUNDED_출금_성공_원장_REJECTED_환불_성공이면_REFUNDED() throws Exception {
        UUID chargeId = manualReview(RECENT_MINUTES_AGO);
        UUID refundTxId = UUID.randomUUID();
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT),
                refundSucceeded(chargeId, refundTxId, AMOUNT), rejected(chargeId));

        resolve(chargeId, UUID.randomUUID(), ResolutionAction.CONFIRM_REFUNDED, "환불 확인", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultStatus").value("REFUNDED"));

        Map<String, Object> row = chargeRow(chargeId);
        assertEquals("REFUNDED", row.get("status"));
        // 정상 환불 흐름과 같은 코드
        assertEquals("LEDGER_REJECTED", row.get("failure_code"));
        assertEquals(refundTxId, row.get("refund_bank_tx_id"));
        assertNull(row.get("ledger_ref"));
        assertResolved(chargeId, ResolutionAction.CONFIRM_REFUNDED, "REFUNDED");
    }

    @Test
    void RESUME_CREDIT_출금_성공_원장_확정된_없음이면_CREDIT_PROCESSING_attempt_0() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        UUID bankTxId = UUID.randomUUID();
        stub(chargeId, debitSucceeded(chargeId, bankTxId, AMOUNT), notFound(), notFound());

        resolve(chargeId, UUID.randomUUID(), ResolutionAction.RESUME_CREDIT, "적립 재개", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultStatus").value("CREDIT_PROCESSING"));

        Map<String, Object> row = chargeRow(chargeId);
        assertEquals("CREDIT_PROCESSING", row.get("status"));
        assertEquals(0, row.get("attempt_count"));
        assertNull(row.get("failure_code"));
        // 비어 있던 출금 거래 ID를 확인한 값으로 채운다
        assertEquals(bankTxId, row.get("bank_tx_id"));
        assertResolved(chargeId, ResolutionAction.RESUME_CREDIT, "CREDIT_PROCESSING");
    }

    @Test
    void RESUME_REFUND_출금_성공_원장_REJECTED_환불_확정된_없음이면_REFUND_PROCESSING_attempt_0() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        UUID bankTxId = UUID.randomUUID();
        stub(chargeId, debitSucceeded(chargeId, bankTxId, AMOUNT), notFound(), rejected(chargeId));

        resolve(chargeId, UUID.randomUUID(), ResolutionAction.RESUME_REFUND, "환불 재개", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultStatus").value("REFUND_PROCESSING"));

        Map<String, Object> row = chargeRow(chargeId);
        assertEquals("REFUND_PROCESSING", row.get("status"));
        assertEquals(0, row.get("attempt_count"));
        // 정상 흐름에서 원장 거절로 REFUND_PROCESSING에 들어올 때와 같은 값
        assertEquals("LEDGER_REJECTED", row.get("failure_code"));
        assertEquals(bankTxId, row.get("bank_tx_id"));
        assertResolved(chargeId, ResolutionAction.RESUME_REFUND, "REFUND_PROCESSING");
    }

    // ---------------------------------------------------------------- 판정 불가

    @Test
    void 판정_불가_금액_불일치는_409이고_변화_없음() throws Exception {
        UUID chargeId = manualReview(RECENT_MINUTES_AGO);
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT - 1), notFound(),
                posted(chargeId, UUID.randomUUID(), AMOUNT));

        assertRejected(chargeId, ResolutionAction.CONFIRM_COMPLETED, "은행 출금 금액 불일치");
    }

    @Test
    void 판정_불가_원장_적립과_환불_성공_동시는_409이고_변화_없음() throws Exception {
        UUID chargeId = manualReview(RECENT_MINUTES_AGO);
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT),
                refundSucceeded(chargeId, UUID.randomUUID(), AMOUNT), posted(chargeId, UUID.randomUUID(), AMOUNT));

        assertRejected(chargeId, ResolutionAction.CONFIRM_COMPLETED, "동시에 있음");
        assertRejected(chargeId, ResolutionAction.CONFIRM_REFUNDED, "동시에 있음");
    }

    @Test
    void 판정_불가_환불_거절은_409이고_변화_없음() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT), refundFailed(chargeId), rejected(chargeId));

        assertRejected(chargeId, ResolutionAction.RESUME_REFUND, "은행 환불이 거절됨");
    }

    @Test
    void 판정_불가_조건_불일치는_가까운_조치로_바꾸지_않고_409() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        // 원장 없음 → RESUME_CREDIT만 맞는데 CONFIRM_COMPLETED 요청
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT), notFound(), notFound());

        assertRejected(chargeId, ResolutionAction.CONFIRM_COMPLETED, "원장이 POSTED 아님");
    }

    @Test
    void 판정_불가_충전_기록의_거래_ID와_다르면_409() throws Exception {
        UUID chargeId = manualReview(RECENT_MINUTES_AGO);
        jdbcTemplate.update("UPDATE charge_requests SET bank_tx_id = ? WHERE id = ?", UUID.randomUUID(), chargeId);
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT), notFound(),
                posted(chargeId, UUID.randomUUID(), AMOUNT));

        assertRejected(chargeId, ResolutionAction.CONFIRM_COMPLETED, "bank_tx_id");
    }

    // ---------------------------------------------------------------- NotFound 확정 시간

    @Test
    void NotFound는_전환_후_설정_시간_전이면_거절_지나면_같은_키로_허용() throws Exception {
        UUID chargeId = manualReview(RECENT_MINUTES_AGO);
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT), notFound(), notFound());
        UUID key = UUID.randomUUID();

        resolve(chargeId, key, ResolutionAction.RESUME_CREDIT, "적립 재개", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHARGE_RESOLUTION_REJECTED"))
                .andExpect(jsonPath("$.error.details[?(@.field == 'reason')].message",
                        hasItem(containsString("확정 시간 전"))));
        assertEquals("MANUAL_REVIEW", chargeRow(chargeId).get("status"));

        // 거절은 키를 소비하지 않는다: 시간이 지난 뒤 같은 키로 다시 판단
        jdbcTemplate.update("UPDATE charge_requests SET updated_at = now() - interval '11 minutes' WHERE id = ?",
                chargeId);
        resolve(chargeId, key, ResolutionAction.RESUME_CREDIT, "적립 재개", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultStatus").value("CREDIT_PROCESSING"));
    }

    @Test
    void 출금_NotFound도_확정_시간이_지나야_CONFIRM_FAILED() throws Exception {
        UUID chargeId = manualReview(RECENT_MINUTES_AGO);
        stub(chargeId, notFound(), notFound(), notFound());

        assertRejected(chargeId, ResolutionAction.CONFIRM_FAILED, "확정 시간 전");

        jdbcTemplate.update("UPDATE charge_requests SET updated_at = now() - interval '11 minutes' WHERE id = ?",
                chargeId);
        resolve(chargeId, UUID.randomUUID(), ResolutionAction.CONFIRM_FAILED, "출금 없음 확인", null)
                .andExpect(status().isOk());
        assertEquals("FAILED", chargeRow(chargeId).get("status"));
    }

    // ---------------------------------------------------------------- 조회 불가

    @Test
    void 조회_Unavailable이면_503이고_변화_없음() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT), notFound(),
                SourceLookup.unavailable("timeout"));
        Map<String, Object> before = chargeRow(chargeId);

        resolve(chargeId, UUID.randomUUID(), ResolutionAction.RESUME_CREDIT, "적립 재개", null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CHARGE_RESOLUTION_SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.details[?(@.field == 'ledger')].message", contains("UNAVAILABLE")));

        assertUnchanged(chargeId, before);
    }

    // ---------------------------------------------------------------- 재개 후 SAGA

    @Test
    void RESUME_CREDIT_후_복구_스케줄러가_기존_SAGA대로_COMPLETED로_진행() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT), notFound(), notFound());
        resolve(chargeId, UUID.randomUUID(), ResolutionAction.RESUME_CREDIT, "적립 재개", null)
                .andExpect(status().isOk());
        UUID ledgerTxId = UUID.randomUUID();
        when(ledgerClient.postCharge(any(), any(), any(), anyLong())).thenReturn(new LedgerPostResult.Posted(ledgerTxId));
        // 복구 대기 시간(30초)이 지난 것으로 둔다
        jdbcTemplate.update("UPDATE charge_requests SET updated_at = now() - interval '31 seconds' WHERE id = ?",
                chargeId);

        new ChargeRecoveryScheduler(recoverySteps, executor, 10, "10s").recover();

        Map<String, Object> row = chargeRow(chargeId);
        assertEquals("COMPLETED", row.get("status"));
        assertEquals(ledgerTxId, row.get("ledger_ref"));
        verify(ledgerClient).postCharge(chargeId, userId, walletId, AMOUNT);
        verify(bankClient, never()).refund(anyString(), any(), anyLong());
    }

    @Test
    void RESUME_REFUND_후_복구_스케줄러가_확인한_출금_ID로_환불해_REFUNDED_bank_tx_id_비어_있음() throws Exception {
        assertResumeRefundRecovers(false);
    }

    @Test
    void RESUME_REFUND_후_복구_스케줄러가_확인한_출금_ID로_환불해_REFUNDED_bank_tx_id_같은_값_있음() throws Exception {
        assertResumeRefundRecovers(true);
    }

    private void assertResumeRefundRecovers(boolean bankTxIdRecorded) throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        UUID bankTxId = UUID.randomUUID();
        if (bankTxIdRecorded) {
            jdbcTemplate.update("UPDATE charge_requests SET bank_tx_id = ? WHERE id = ?", bankTxId, chargeId);
        }
        assertEquals(bankTxIdRecorded ? bankTxId : null, chargeRow(chargeId).get("bank_tx_id"));
        stub(chargeId, debitSucceeded(chargeId, bankTxId, AMOUNT), notFound(), rejected(chargeId));

        resolve(chargeId, UUID.randomUUID(), ResolutionAction.RESUME_REFUND, "환불 재개", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultStatus").value("REFUND_PROCESSING"));
        Map<String, Object> resumed = chargeRow(chargeId);
        assertEquals("REFUND_PROCESSING", resumed.get("status"));
        assertEquals(bankTxId, resumed.get("bank_tx_id"));

        String refundRequestId = jdbcTemplate.queryForObject(
                "SELECT refund_request_id FROM charge_requests WHERE id = ?", String.class, chargeId);
        UUID refundTxId = UUID.randomUUID();
        when(bankClient.refund(anyString(), any(), anyLong())).thenReturn(new BankResult.Succeeded(refundTxId));
        // 복구 대기 시간(30초)이 지난 것으로 둔다
        jdbcTemplate.update("UPDATE charge_requests SET updated_at = now() - interval '31 seconds' WHERE id = ?",
                chargeId);

        new ChargeRecoveryScheduler(recoverySteps, executor, 10, "10s").recover();

        verify(bankClient, times(1)).refund(anyString(), any(), anyLong());
        verify(bankClient).refund(refundRequestId, bankTxId, AMOUNT);
        Map<String, Object> row = chargeRow(chargeId);
        assertEquals("REFUNDED", row.get("status"));
        assertEquals(refundTxId, row.get("refund_bank_tx_id"));
        assertEquals("LEDGER_REJECTED", row.get("failure_code"));
    }

    // ---------------------------------------------------------------- 동시성

    @Test
    void 다른_키로_동시_보정_2건이면_하나만_성공하고_나머지는_409() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        stubConcurrent(chargeId, 2);

        List<Object> outcomes = runConcurrently(
                () -> resolutionService.resolve(command(chargeId, UUID.randomUUID(), "재개 A")),
                () -> resolutionService.resolve(command(chargeId, UUID.randomUUID(), "재개 B")));

        // 정확히 2건: 성공 1건 + 409 CHARGE_NOT_IN_MANUAL_REVIEW 1건, 그 외 결과·예외 없음
        assertEquals(2, outcomes.size());
        List<ChargeResolutionResult> successes = outcomes.stream()
                .filter(ChargeResolutionResult.class::isInstance).map(ChargeResolutionResult.class::cast).toList();
        List<BusinessException> failures = outcomes.stream()
                .filter(BusinessException.class::isInstance).map(BusinessException.class::cast).toList();
        assertEquals(1, successes.size(), () -> "outcomes=" + outcomes);
        assertEquals(1, failures.size(), () -> "outcomes=" + outcomes);
        BusinessException failure = failures.get(0);
        assertEquals(409, failure.getErrorCode().getStatus());
        assertEquals("CHARGE_NOT_IN_MANUAL_REVIEW", failure.getErrorCode().getCode());

        // 최종 상태는 이긴 요청의 조치 결과와 같다
        ChargeResolutionResult winner = successes.get(0);
        assertEquals(ChargeStatus.CREDIT_PROCESSING, winner.resultStatus());
        assertEquals(winner.resultStatus().name(), chargeRow(chargeId).get("status"));
        assertEquals(1, resolutionCount(chargeId));
        assertEquals(1, resolvedHistoryCount(chargeId));
    }

    @Test
    void 같은_키_같은_내용_동시_2건이면_둘_다_같은_결과이고_반영은_1번() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        stubConcurrent(chargeId, 2);
        UUID key = UUID.randomUUID();

        List<Object> outcomes = runConcurrently(
                () -> resolutionService.resolve(command(chargeId, key, "재개")),
                () -> resolutionService.resolve(command(chargeId, key, "재개")));

        ChargeResolutionResult first = assertInstanceOf(ChargeResolutionResult.class, outcomes.get(0));
        ChargeResolutionResult second = assertInstanceOf(ChargeResolutionResult.class, outcomes.get(1));
        assertEquals(first, second);
        assertEquals(1, resolutionCount(chargeId));
        assertEquals(1, resolvedHistoryCount(chargeId));
        assertEquals("CREDIT_PROCESSING", chargeRow(chargeId).get("status"));
    }

    // ---------------------------------------------------------------- Idempotency-Key

    @Test
    void 같은_키_재시도는_은행_원장_조회_포트_호출_0회로_처음_결과_그대로() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT), notFound(), notFound());
        UUID key = UUID.randomUUID();
        String first = resolve(chargeId, key, ResolutionAction.RESUME_CREDIT, "적립 재개", null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        clearInvocations(bankQuery, ledgerQuery);

        String second = resolve(chargeId, key, ResolutionAction.RESUME_CREDIT, "적립 재개", null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals(first, second);
        verify(bankQuery, never()).findTransaction(anyString());
        verify(ledgerQuery, never()).findChargeTransaction(any());
        assertEquals(1, resolutionCount(chargeId));
    }

    @Test
    void 같은_키_다른_내용은_409() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT), notFound(), notFound());
        UUID key = UUID.randomUUID();
        resolve(chargeId, key, ResolutionAction.RESUME_CREDIT, "적립 재개", null).andExpect(status().isOk());

        resolve(chargeId, key, ResolutionAction.RESUME_CREDIT, "다른 사유", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHARGE_RESOLUTION_IDEMPOTENCY_CONFLICT"));
        resolve(chargeId, key, ResolutionAction.CONFIRM_FAILED, "적립 재개", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHARGE_RESOLUTION_IDEMPOTENCY_CONFLICT"));
        assertEquals(1, resolutionCount(chargeId));
    }

    @Test
    void 다른_키인데_이미_보정됨이면_409() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT), notFound(), notFound());
        resolve(chargeId, UUID.randomUUID(), ResolutionAction.RESUME_CREDIT, "적립 재개", null)
                .andExpect(status().isOk());

        resolve(chargeId, UUID.randomUUID(), ResolutionAction.RESUME_CREDIT, "적립 재개", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHARGE_NOT_IN_MANUAL_REVIEW"));
        assertEquals(1, resolutionCount(chargeId));
    }

    // ---------------------------------------------------------------- 대사 결과 연결

    @Test
    void 대사_결과_연결은_원래_판정을_유지하고_RESOLVED() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        UUID resultId = insertChargeResult(chargeId);
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT), notFound(), notFound());

        String body = resolve(chargeId, UUID.randomUUID(), ResolutionAction.RESUME_CREDIT, "적립 재개", resultId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reconciliationResultId").value(resultId.toString()))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> result = jdbcTemplate.queryForMap(
                "SELECT mismatch_type, snapshot::text AS snapshot, resolution_status, resolution_id "
                        + "FROM reconciliation_results WHERE id = ?", resultId);
        assertEquals("REVIEW_REQUIRED", result.get("mismatch_type"));
        assertEquals("{\"chargeStatus\": \"MANUAL_REVIEW\"}", result.get("snapshot"));
        assertEquals("RESOLVED", result.get("resolution_status"));
        UUID resolutionId = jdbcTemplate.queryForObject(
                "SELECT id FROM charge_resolutions WHERE charge_id = ?", UUID.class, chargeId);
        assertEquals(resolutionId, result.get("resolution_id"));
        assertTrue(body.contains(resolutionId.toString()));
    }

    @Test
    void 다른_충전의_대사_결과_ID면_400이고_변화_없음() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        UUID otherCharge = manualReview(CONFIRMED_MINUTES_AGO);
        UUID otherResult = insertChargeResult(otherCharge);
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT), notFound(), notFound());
        Map<String, Object> before = chargeRow(chargeId);

        resolve(chargeId, UUID.randomUUID(), ResolutionAction.RESUME_CREDIT, "적립 재개", otherResult)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RECONCILIATION_RESULT_NOT_FOR_CHARGE"));
        resolve(chargeId, UUID.randomUUID(), ResolutionAction.RESUME_CREDIT, "적립 재개", UUID.randomUUID())
                .andExpect(status().isBadRequest());

        assertUnchanged(chargeId, before);
        assertEquals("PENDING", jdbcTemplate.queryForObject(
                "SELECT resolution_status FROM reconciliation_results WHERE id = ?", String.class, otherResult));
    }

    // ---------------------------------------------------------------- review

    @Test
    void review는_아무것도_바꾸지_않고_가능한_조치와_불가_사유를_준다() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        UUID bankTxId = UUID.randomUUID();
        stub(chargeId, debitSucceeded(chargeId, bankTxId, AMOUNT), notFound(), notFound());
        Map<String, Object> before = chargeRow(chargeId);

        asMaster(get(PATH + chargeId + "/review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.data.withdrawal.lookup").value("FOUND"))
                .andExpect(jsonPath("$.data.withdrawal.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.withdrawal.transactionId").value(bankTxId.toString()))
                .andExpect(jsonPath("$.data.ledger.lookup").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data.checkedAt").exists())
                .andExpect(jsonPath("$.data.availableActions", contains("RESUME_CREDIT")))
                .andExpect(jsonPath("$.data.unavailableActions[*].action", hasItems(
                        "CONFIRM_COMPLETED", "CONFIRM_FAILED", "CONFIRM_REFUNDED", "RESUME_REFUND")))
                .andExpect(jsonPath("$.data.unavailableActions[*].action", not(hasItem("RESUME_CREDIT"))))
                .andExpect(jsonPath("$.data.unavailableActions[?(@.action == 'CONFIRM_COMPLETED')].reasons[*]",
                        hasItem(containsString("원장이 POSTED 아님"))));

        assertUnchanged(chargeId, before);
    }

    @Test
    void review는_조회_불가를_UNAVAILABLE로_보여_주고_가능한_조치가_없다() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        stub(chargeId, SourceLookup.unavailable("timeout"), notFound(), notFound());

        asMaster(get(PATH + chargeId + "/review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawal.lookup").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.availableActions").isEmpty())
                .andExpect(jsonPath("$.data.blockers", hasItem(containsString("조회 불가"))));
    }

    // ---------------------------------------------------------------- 403·404·409·400

    @Test
    void MASTER가_아니면_403() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        Map<String, Object> before = chargeRow(chargeId);

        asUser(get(PATH + chargeId + "/review")).andExpect(status().isForbidden());
        asUser(resolveRequest(chargeId, UUID.randomUUID(), ResolutionAction.RESUME_CREDIT, "재개", null))
                .andExpect(status().isForbidden());

        assertUnchanged(chargeId, before);
        verify(bankQuery, never()).findTransaction(anyString());
    }

    @Test
    void 충전이_없으면_404() throws Exception {
        UUID missing = UUID.randomUUID();
        asMaster(get(PATH + missing + "/review")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHARGE_NOT_FOUND"));
        resolve(missing, UUID.randomUUID(), ResolutionAction.RESUME_CREDIT, "재개", null)
                .andExpect(status().isNotFound());
    }

    @Test
    void MANUAL_REVIEW가_아니면_409() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        jdbcTemplate.update("UPDATE charge_requests SET status = 'COMPLETED' WHERE id = ?", chargeId);

        asMaster(get(PATH + chargeId + "/review")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHARGE_NOT_IN_MANUAL_REVIEW"));
        resolve(chargeId, UUID.randomUUID(), ResolutionAction.CONFIRM_COMPLETED, "확정", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHARGE_NOT_IN_MANUAL_REVIEW"));
        verify(bankQuery, never()).findTransaction(anyString());
    }

    @Test
    void 사유_500자_초과_키_없음_조치_없음은_400이고_자르지_않는다() throws Exception {
        UUID chargeId = manualReview(CONFIRMED_MINUTES_AGO);
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT), notFound(), notFound());

        resolve(chargeId, UUID.randomUUID(), ResolutionAction.RESUME_CREDIT, "가".repeat(501), null)
                .andExpect(status().isBadRequest());
        resolve(chargeId, UUID.randomUUID(), ResolutionAction.RESUME_CREDIT, " ", null)
                .andExpect(status().isBadRequest());
        asMaster(post(PATH + chargeId + "/resolve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"RESUME_CREDIT\",\"reason\":\"재개\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHARGE_INVALID_IDEMPOTENCY_KEY"));
        asMaster(post(PATH + chargeId + "/resolve").header("Idempotency-Key", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"RESUME_CREDIT\",\"reason\":\"재개\"}"))
                .andExpect(status().isBadRequest());
        asMaster(post(PATH + chargeId + "/resolve").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"NOPE\",\"reason\":\"재개\"}"))
                .andExpect(status().isBadRequest());
        assertEquals("MANUAL_REVIEW", chargeRow(chargeId).get("status"));

        // 정확히 500자는 그대로 저장된다
        String reason = "가".repeat(500);
        resolve(chargeId, UUID.randomUUID(), ResolutionAction.RESUME_CREDIT, reason, null).andExpect(status().isOk());
        assertEquals(reason, jdbcTemplate.queryForObject(
                "SELECT reason_detail FROM charge_status_history WHERE charge_id = ? AND resolution_id IS NOT NULL",
                String.class, chargeId));
    }

    // ---------------------------------------------------------------- helpers

    /** MANUAL_REVIEW(RETRY_EXHAUSTED, attempt 5)로 minutesAgo분 전에 전환된 충전. 거래 ID는 비어 있다 */
    private UUID manualReview(int minutesAgo) {
        ChargeRequest charge = chargeSteps.accept(ChargeRequest.create(userId, walletId, AMOUNT, Currency.KRW,
                BankAccount.encrypt("004", "1234567890", cipher), "홍길동", UUID.randomUUID()));
        jdbcTemplate.update("""
                UPDATE charge_requests
                   SET status = 'MANUAL_REVIEW', failure_code = 'RETRY_EXHAUSTED', attempt_count = 5,
                       updated_at = now() - make_interval(mins => ?)
                 WHERE id = ?
                """, minutesAgo, charge.getId());
        // 진입 이력 (SAGA가 결과 모름 한도로 전환할 때 남기는 줄과 같은 모양)
        jdbcTemplate.update("""
                INSERT INTO charge_status_history (id, charge_id, from_status, to_status, reason_code)
                VALUES (?, ?, 'BANK_PROCESSING', 'MANUAL_REVIEW', 'RETRY_EXHAUSTED')
                """, UUID.randomUUID(), charge.getId());
        return charge.getId();
    }

    private void stub(UUID chargeId, SourceLookup<BankTransactionRecord> debit,
                      SourceLookup<BankTransactionRecord> refund, SourceLookup<LedgerTransactionRecord> ledger) {
        Map<String, Object> ids = jdbcTemplate.queryForMap(
                "SELECT bank_request_id, refund_request_id FROM charge_requests WHERE id = ?", chargeId);
        when(bankQuery.findTransaction((String) ids.get("bank_request_id"))).thenReturn(debit);
        when(bankQuery.findTransaction((String) ids.get("refund_request_id"))).thenReturn(refund);
        when(ledgerQuery.findChargeTransaction(chargeId)).thenReturn(ledger);
    }

    /** RESUME_CREDIT이 맞는 사실. 원장 조회에서 parties개 요청이 모두 모일 때까지 기다려, 반영 단계에서 경합하게 한다 */
    private void stubConcurrent(UUID chargeId, int parties) {
        stub(chargeId, debitSucceeded(chargeId, UUID.randomUUID(), AMOUNT), notFound(), notFound());
        CyclicBarrier barrier = new CyclicBarrier(parties);
        when(ledgerQuery.findChargeTransaction(chargeId)).thenAnswer(invocation -> {
            barrier.await(5, TimeUnit.SECONDS);
            return SourceLookup.notFound();
        });
    }

    private ResolveChargeCommand command(UUID chargeId, UUID key, String reason) {
        return new ResolveChargeCommand(chargeId, OPERATOR, key.toString(), ResolutionAction.RESUME_CREDIT, reason,
                null);
    }

    /** 각 작업의 결과 또는 던진 예외 */
    @SafeVarargs
    private List<Object> runConcurrently(Callable<Object>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    try {
                        return task.call();
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(20, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertRejected(UUID chargeId, ResolutionAction action, String reasonFragment) throws Exception {
        Map<String, Object> before = chargeRow(chargeId);
        resolve(chargeId, UUID.randomUUID(), action, "확인", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHARGE_RESOLUTION_REJECTED"))
                .andExpect(jsonPath("$.error.details[?(@.field == 'reason')].message",
                        hasItem(containsString(reasonFragment))))
                .andExpect(jsonPath("$.error.details[?(@.field == 'withdrawal')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field == 'checkedAt')]").exists());
        assertUnchanged(chargeId, before);
    }

    private void assertUnchanged(UUID chargeId, Map<String, Object> before) {
        assertEquals(before, chargeRow(chargeId));
        // 접수 + MANUAL_REVIEW 진입 2줄 그대로
        assertEquals(2, historyCount(chargeId));
        assertEquals(0, resolutionCount(chargeId));
    }

    private void assertResolved(UUID chargeId, ResolutionAction action, String toStatus) {
        Map<String, Object> history = jdbcTemplate.queryForMap("""
                SELECT h.from_status, h.to_status, h.reason_code, h.resolution_id
                  FROM charge_status_history h
                 WHERE h.charge_id = ? AND h.resolution_id IS NOT NULL
                """, chargeId);
        assertEquals("MANUAL_REVIEW", history.get("from_status"));
        assertEquals(toStatus, history.get("to_status"));
        assertEquals("RESOLVE_" + action.name(), history.get("reason_code"));
        Map<String, Object> resolution = jdbcTemplate.queryForMap(
                "SELECT id, action, result_status FROM charge_resolutions WHERE charge_id = ?", chargeId);
        assertEquals(resolution.get("id"), history.get("resolution_id"));
        assertEquals(action.name(), resolution.get("action"));
        assertEquals(toStatus, resolution.get("result_status"));
        assertNotNull(resolution.get("id"));
    }

    private UUID insertChargeResult(UUID chargeId) {
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO reconciliation_runs (id, period_from, period_to, status, started_at, "
                + "finished_at, run_type) VALUES (?, now() - interval '2 hours', now() - interval '1 hour', "
                + "'COMPLETED', now(), now(), 'AUTO')", runId);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO reconciliation_results (id, run_id, charge_id, mismatch_type, detail, "
                + "snapshot, detected_at) VALUES (?, ?, ?, 'REVIEW_REQUIRED', 'd', "
                + "'{\"chargeStatus\": \"MANUAL_REVIEW\"}'::jsonb, now())", id, runId, chargeId);
        return id;
    }

    private Map<String, Object> chargeRow(UUID chargeId) {
        return jdbcTemplate.queryForMap("""
                SELECT status, failure_code, attempt_count, bank_tx_id, ledger_ref, refund_bank_tx_id, updated_at
                  FROM charge_requests WHERE id = ?
                """, chargeId);
    }

    private int historyCount(UUID chargeId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM charge_status_history WHERE charge_id = ?",
                Integer.class, chargeId);
    }

    private int resolvedHistoryCount(UUID chargeId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM charge_status_history "
                + "WHERE charge_id = ? AND resolution_id IS NOT NULL", Integer.class, chargeId);
    }

    private int resolutionCount(UUID chargeId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM charge_resolutions WHERE charge_id = ?",
                Integer.class, chargeId);
    }

    private ResultActions resolve(UUID chargeId, UUID key, ResolutionAction action, String reason, UUID resultId)
            throws Exception {
        return asMaster(resolveRequest(chargeId, key, action, reason, resultId));
    }

    private MockHttpServletRequestBuilder resolveRequest(UUID chargeId, UUID key, ResolutionAction action,
                                                         String reason, UUID resultId) {
        String body = "{\"action\":\"" + action.name() + "\",\"reason\":\"" + reason + "\""
                + (resultId != null ? ",\"reconciliationResultId\":\"" + resultId + "\"" : "") + "}";
        return post(PATH + chargeId + "/resolve").header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 충전 주인 회원 */
    private ResultActions asOwner(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("User-Id", userId.toString()).header("User-Role", "USER"));
    }

    private ResultActions asUser(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("User-Id", UUID.randomUUID().toString()).header("User-Role", "USER"));
    }

    private ResultActions asMaster(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("User-Id", OPERATOR.toString()).header("User-Role", "MASTER"));
    }

    private static SourceLookup<BankTransactionRecord> debitSucceeded(UUID chargeId, UUID txId, long amount) {
        return SourceLookup.found(new BankTransactionRecord("debit-" + chargeId, txId, "DEBIT", "SUCCEEDED", amount));
    }

    private static SourceLookup<BankTransactionRecord> debitFailed(UUID chargeId, String bankFailureCode) {
        return SourceLookup.found(new BankTransactionRecord("debit-" + chargeId, null, "DEBIT", "FAILED", AMOUNT,
                bankFailureCode));
    }

    private static SourceLookup<BankTransactionRecord> refundSucceeded(UUID chargeId, UUID txId, long amount) {
        return SourceLookup.found(new BankTransactionRecord("refund-" + chargeId, txId, "REFUND", "SUCCEEDED",
                amount));
    }

    private static SourceLookup<BankTransactionRecord> refundFailed(UUID chargeId) {
        return SourceLookup.found(new BankTransactionRecord("refund-" + chargeId, null, "REFUND", "FAILED", AMOUNT));
    }

    private SourceLookup<LedgerTransactionRecord> posted(UUID chargeId, UUID txId, long amount) {
        return SourceLookup.found(new LedgerTransactionRecord(txId, "POSTED", chargeId, List.of(
                new LedgerTransactionRecord.LedgerEntryRecord("SYSTEM", null, "DEBIT", "KRW", amount, "CHARGE_SOURCE"),
                new LedgerTransactionRecord.LedgerEntryRecord("USER", walletId, "CREDIT", "KRW", amount,
                        "CHARGE_IN"))));
    }

    private static SourceLookup<LedgerTransactionRecord> rejected(UUID chargeId) {
        return SourceLookup.found(new LedgerTransactionRecord(UUID.randomUUID(), "REJECTED", chargeId, List.of()));
    }

    private static <T> SourceLookup<T> notFound() {
        return SourceLookup.notFound();
    }
}
