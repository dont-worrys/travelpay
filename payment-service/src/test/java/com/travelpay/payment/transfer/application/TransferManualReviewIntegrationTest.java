package com.travelpay.payment.transfer.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.payment.transfer.client.LedgerTransactionRequest;
import com.travelpay.payment.transfer.client.LedgerTransactionResponse;
import com.travelpay.payment.transfer.client.TransferLedgerClient;
import com.travelpay.payment.transfer.client.TransferLedgerException;
import com.travelpay.payment.transfer.client.TransferUserReader;
import com.travelpay.payment.transfer.client.TransferWalletReader;
import com.travelpay.payment.transfer.client.UserRecipientResponse;
import com.travelpay.payment.transfer.controller.TransferController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TC-21: 원장 결과 모름이 반복되면 재시도 한도(처음 요청 포함 5회)에서 MANUAL_REVIEW(RETRY_EXHAUSTED)로 바뀌는지
 * 실제 PostgreSQL(Flyway 스키마·CHECK 제약·조건부 UPDATE·복구 대상 쿼리)과 송금 API(MockMvc)로 확인합니다.
 * 원장·지갑·회원 조회만 Mock으로 바꿉니다. 복구 스케줄러는 끄고 TransferService.recoverDue를 직접 호출합니다.
 * 각 테스트는 트랜잭션 안에서 실행 후 롤백되며, PAYMENT_DB_URL 등 DB 환경 변수가 있을 때만 실행합니다.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "transfer.recovery.enabled=false"})
@Transactional
@EnabledIfEnvironmentVariable(named = "PAYMENT_DB_URL", matches = ".+")
class TransferManualReviewIntegrationTest {

    private static final UUID SOURCE_WALLET_ID = UUID.randomUUID();
    private static final UUID TARGET_WALLET_ID = UUID.randomUUID();
    private static final String KEY = "tc21-key";
    private static final String BODY = """
            {"receiverId":"%s","amount":3000,"currency":"KRW","memo":"TC-21"}""";
    private static final TransferLedgerException RESULT_UNKNOWN =
            TransferLedgerException.resultUnknown("원장 거래 등록 실패 status=-1 code=-", null);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TransferService transferService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private TransferLedgerClient ledgerClient;

    @MockitoBean
    private TransferWalletReader walletReader;

    @MockitoBean
    private TransferUserReader userReader;

    private MockMvc mvc;
    private UUID senderId;
    private UUID receiverId;

    @BeforeEach
    void setUp() {
        // 로컬 DB에 다른 PROCESSING 송금이 있으면 복구가 그 송금부터 시도해 결과가 섞이므로 실행하지 않습니다.
        Long otherProcessing = jdbcTemplate.queryForObject(
                "select count(*) from transfer where status = 'PROCESSING'", Long.class);
        assumeTrue(otherProcessing == 0, "로컬 DB에 PROCESSING 송금이 없어야 합니다: " + otherProcessing);

        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        senderId = UUID.randomUUID();
        receiverId = UUID.randomUUID();
        when(walletReader.findActiveWalletId(senderId)).thenReturn(Optional.of(SOURCE_WALLET_ID));
        when(walletReader.findActiveWalletId(receiverId)).thenReturn(Optional.of(TARGET_WALLET_ID));
        when(userReader.findRecipient(receiverId))
                .thenReturn(Optional.of(new UserRecipientResponse(receiverId, "홍길동")));
    }

    /** TC-21-I1: 결과 모름 5회 → MANUAL_REVIEW(RETRY_EXHAUSTED). 시도별 대기 시간, 한도 이후 재시도 없음, API 응답까지 확인합니다. */
    @Test
    void movesToManualReviewWhenLedgerResultStaysUnknownUntilRetryLimit() throws Exception {
        when(ledgerClient.register(any(LedgerTransactionRequest.class))).thenThrow(RESULT_UNKNOWN);

        // 1회차: 송금 요청 → 결과 모름 → 202 PROCESSING, attempt_count 1
        String body = requestTransfer()
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andReturn().getResponse().getContentAsString();
        UUID transferId = UUID.fromString(JsonPath.read(body, "$.data.transferId"));
        assertRow(transferId, "PROCESSING", 1, null);
        verify(ledgerClient, times(1)).register(any());

        // 2~5회차: 직전 기록 시각 + 대기 시간(30초 × 2^(attempt_count-1) = 30초→60초→120초→240초)까지는 재시도하지 않고, 지나면 재시도합니다.
        long[] waitSeconds = {30, 60, 120, 240};
        for (int attempt = 2; attempt <= 5; attempt++) {
            Instant lastRecordedAt = updatedAt(transferId);
            Instant dueAt = lastRecordedAt.plusSeconds(waitSeconds[attempt - 2]);

            transferService.recoverDue(dueAt, 10);
            verify(ledgerClient, times(attempt - 1)).register(any());

            transferService.recoverDue(dueAt.plusMillis(1), 10);
            verify(ledgerClient, times(attempt)).register(any());
            if (attempt < 5) {
                assertRow(transferId, "PROCESSING", attempt, null);
            }
        }

        // 한도 도달: MANUAL_REVIEW + RETRY_EXHAUSTED, FAILED로 단정하지 않음(failure_reason 없음)
        assertRow(transferId, "MANUAL_REVIEW", 5, "RETRY_EXHAUSTED");

        // 한도 이후: 복구 대상에서 빠지고(대기 시간과 무관), 같은 키 재요청도 원장을 다시 호출하지 않습니다.
        transferService.recoverDue(Instant.now().plusSeconds(86_400), 10);
        requestTransfer()
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.data.status").value("MANUAL_REVIEW"));
        verify(ledgerClient, times(5)).register(any());
        assertRow(transferId, "MANUAL_REVIEW", 5, "RETRY_EXHAUSTED");

        // 상세·목록은 MANUAL_REVIEW, 취소는 409(REQUESTED만 취소 가능)
        mvc.perform(get("/api/v1/transfers/{transferId}", transferId)
                        .header(AuthHeaders.USER_ID, senderId.toString())
                        .header(AuthHeaders.USER_ROLE, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MANUAL_REVIEW"));
        mvc.perform(get("/api/v1/transfers")
                        .header(AuthHeaders.USER_ID, senderId.toString())
                        .header(AuthHeaders.USER_ROLE, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.data.items[0].status").value("MANUAL_REVIEW"));
        mvc.perform(post("/api/v1/transfers/{transferId}/cancel", transferId)
                        .header(AuthHeaders.USER_ID, senderId.toString())
                        .header(AuthHeaders.USER_ROLE, "USER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMMON_CONFLICT"));
        assertRow(transferId, "MANUAL_REVIEW", 5, "RETRY_EXHAUSTED");

        // 운영 지표(게이지 transfer.manual_review)의 원천 값에 포함됩니다.
        assertEquals(countManualReviewExcept(transferId) + 1, transferService.countManualReview());
    }

    /** TC-21-I2: 원장이 요청을 받아들이지 않으면(409 등) 시도 횟수와 무관하게 즉시 MANUAL_REVIEW(LEDGER_REQUEST_NOT_ACCEPTED)입니다. */
    @Test
    void movesToManualReviewImmediatelyWhenLedgerDoesNotAcceptRequest() throws Exception {
        when(ledgerClient.register(any(LedgerTransactionRequest.class))).thenThrow(
                TransferLedgerException.requestNotAccepted(
                        "원장 거래 등록 실패 status=409 code=LEDGER_REFERENCE_CONFLICT", null));

        String body = requestTransfer()
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("MANUAL_REVIEW"))
                .andReturn().getResponse().getContentAsString();
        UUID transferId = UUID.fromString(JsonPath.read(body, "$.data.transferId"));

        assertRow(transferId, "MANUAL_REVIEW", 0, "LEDGER_REQUEST_NOT_ACCEPTED");
        transferService.recoverDue(Instant.now().plusSeconds(86_400), 10);
        verify(ledgerClient, times(1)).register(any());
    }

    /** TC-21-I3: 한도 전에 원장이 응답하면 COMPLETED로 확정되고 MANUAL_REVIEW로 가지 않습니다. 시도 횟수는 결과 모름 횟수만 남습니다. */
    @Test
    void completesWithoutManualReviewWhenLedgerRecoversBeforeRetryLimit() throws Exception {
        when(ledgerClient.register(any(LedgerTransactionRequest.class)))
                .thenThrow(RESULT_UNKNOWN)
                .thenThrow(RESULT_UNKNOWN)
                .thenReturn(LedgerTransactionResponse.posted(UUID.randomUUID()));

        String body = requestTransfer()
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID transferId = UUID.fromString(JsonPath.read(body, "$.data.transferId"));
        transferService.recoverDue(updatedAt(transferId).plusSeconds(31), 10);
        transferService.recoverDue(updatedAt(transferId).plusSeconds(61), 10);

        Map<String, Object> row = row(transferId);
        assertEquals("COMPLETED", row.get("status"));
        assertEquals(2, ((Number) row.get("attempt_count")).intValue());
        assertNull(row.get("review_reason"));
        assertNull(row.get("failure_reason"));
        verify(ledgerClient, times(3)).register(any());

        transferService.recoverDue(Instant.now().plusSeconds(86_400), 10);
        verify(ledgerClient, times(3)).register(any());
        requestTransfer()
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    private ResultActions requestTransfer() throws Exception {
        return mvc.perform(post("/api/v1/transfers")
                .header(AuthHeaders.USER_ID, senderId.toString())
                .header(AuthHeaders.USER_ROLE, "USER")
                .header(TransferController.IDEMPOTENCY_KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.formatted(receiverId)));
    }

    private void assertRow(UUID transferId, String status, int attemptCount, String reviewReason) {
        Map<String, Object> row = row(transferId);
        assertEquals(status, row.get("status"));
        assertEquals(attemptCount, ((Number) row.get("attempt_count")).intValue());
        assertEquals(reviewReason, row.get("review_reason"));
        assertNull(row.get("failure_reason"));
    }

    private Map<String, Object> row(UUID transferId) {
        return jdbcTemplate.queryForMap(
                "select status, attempt_count, review_reason, failure_reason from transfer where id = ?", transferId);
    }

    private Instant updatedAt(UUID transferId) {
        return jdbcTemplate.queryForObject("select updated_at from transfer where id = ?", Timestamp.class, transferId)
                .toInstant();
    }

    private long countManualReviewExcept(UUID transferId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from transfer where status = 'MANUAL_REVIEW' and id <> ?", Long.class, transferId);
    }
}
