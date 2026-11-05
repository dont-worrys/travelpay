package com.travelpay.payment.transfer.integration;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi;
import com.travelpay.payment.transfer.application.TransferService;
import com.travelpay.payment.transfer.support.TransferApiIntegrationSupport;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MANUAL_REVIEW 운영자 확정 API를 실제 MVC·서비스·PostgreSQL로 확인합니다. 원장 결과 조회(Feign)만 Mock입니다.
 * 원장 결과와 맞는 확정만 반영되고, 맞지 않으면 상태·이력이 바뀌지 않는지 확인합니다.
 */
class AdminTransferResolveIntegrationTest extends TransferApiIntegrationSupport {

    private static final Instant CREATED = Instant.parse("2099-02-01T00:00:00Z");
    private static final String RETRY_EXHAUSTED = "RETRY_EXHAUSTED";
    private static final String NOT_ACCEPTED = "LEDGER_REQUEST_NOT_ACCEPTED";

    @Autowired
    private TransferService transferService;

    private final UUID masterId = UUID.randomUUID();
    private final UUID ledgerTransactionId = UUID.randomUUID();

    @Test
    void completesWhenLedgerPostedAndRecordsHistory() throws Exception {
        UUID transferId = manualReview(RETRY_EXHAUSTED);
        givenLedger(transferId, "POSTED", null);

        resolve(transferId, "COMPLETED", ledgerTransactionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.reviewReason").doesNotExist())
                .andExpect(jsonPath("$.data.failureReason").doesNotExist())
                .andExpect(jsonPath("$.data.attemptCount").value(5));

        Map<String, Object> row = row(transferId);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("review_reason")).isNull();
        mvc.perform(asMaster(get(history(transferId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fromStatus").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.data[0].toStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data[0].reason").value("원장 POSTED 확인"))
                .andExpect(jsonPath("$.data[0].actorType").value("OPERATOR"))
                .andExpect(jsonPath("$.data[0].actorId").value(masterId.toString()))
                .andExpect(jsonPath("$.data[0].ledgerTransactionId").value(ledgerTransactionId.toString()));
    }

    @Test
    void sendersReplayAfterCompletionGetsCompletedTransfer() throws Exception {
        UUID transferId = manualReview(RETRY_EXHAUSTED);
        givenLedger(transferId, "POSTED", null);
        resolve(transferId, "COMPLETED", ledgerTransactionId).andExpect(status().isOk());

        mvc.perform(asUser(get("/api/v1/transfers/" + transferId), senderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void failsWithLedgerRejectReason() throws Exception {
        UUID insufficient = manualReview(RETRY_EXHAUSTED);
        givenLedger(insufficient, "REJECTED", "INSUFFICIENT_BALANCE");
        UUID suspended = manualReview(RETRY_EXHAUSTED);
        givenLedger(suspended, "REJECTED", "WALLET_SUSPENDED");

        resolve(insufficient, "FAILED", ledgerTransactionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value("INSUFFICIENT_BALANCE"));
        resolve(suspended, "FAILED", ledgerTransactionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failureReason").value("ACCOUNT_UNAVAILABLE"));
        assertThat(row(suspended).get("failure_reason")).isEqualTo("ACCOUNT_UNAVAILABLE");
    }

    @Test
    void returnsToProcessingWhenLedgerHasNoRecordAndRecoveryCompletesIt() throws Exception {
        UUID transferId = manualReview(RETRY_EXHAUSTED);
        givenLedgerNotFound(transferId);

        resolve(transferId, "PROCESSING", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.attemptCount").value(0))
                .andExpect(jsonPath("$.data.reviewReason").doesNotExist());
        mvc.perform(asMaster(get(history(transferId))))
                .andExpect(jsonPath("$.data[0].toStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.data[0].ledgerTransactionId").doesNotExist());

        // 시도 횟수 0이라 30초 뒤 복구 대상이 되고, 같은 reference로 재전송해 확정됩니다.
        when(ledgerFeignApi.register(any())).thenReturn(posted(UUID.randomUUID()));
        entityManager.clear();
        transferService.recoverDue(Instant.now().plusSeconds(31), 10);
        assertThat(row(transferId).get("status")).isEqualTo("COMPLETED");

        // 재처리로 돌린 요청을 다시 보내면 복구로 바뀐 현재 상태를 돌려줍니다.
        resolve(transferId, "PROCESSING", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertThat(historyCount(transferId)).isEqualTo(1);
    }

    @Test
    void rejectsResolutionThatDoesNotMatchLedger() throws Exception {
        UUID posted = manualReview(RETRY_EXHAUSTED);
        givenLedger(posted, "POSTED", null);
        UUID rejected = manualReview(RETRY_EXHAUSTED);
        givenLedger(rejected, "REJECTED", "INSUFFICIENT_BALANCE");
        UUID missing = manualReview(RETRY_EXHAUSTED);
        givenLedgerNotFound(missing);

        expectMismatch(resolve(posted, "FAILED", ledgerTransactionId));
        expectMismatch(resolve(posted, "PROCESSING", null));
        expectMismatch(resolve(posted, "COMPLETED", UUID.randomUUID()));
        expectMismatch(resolve(rejected, "COMPLETED", ledgerTransactionId));
        expectMismatch(resolve(missing, "COMPLETED", ledgerTransactionId));
        expectMismatch(resolve(missing, "FAILED", ledgerTransactionId));

        assertUnchanged(posted);
        assertUnchanged(rejected);
        assertUnchanged(missing);
    }

    @Test
    void completesNotAcceptedTransferWhenLedgerEntriesMatch() throws Exception {
        UUID transferId = manualReview(NOT_ACCEPTED);
        // 원장 항목 순서는 송금 요청과 달라도 됩니다.
        givenPostedLedger(transferId, List.of(
                entry("USER", targetWalletId, "KRW", "CREDIT", 1000L, "TRANSFER_IN"),
                entry("USER", sourceWalletId, "KRW", "DEBIT", 1000L, "TRANSFER_OUT")));

        resolve(transferId, "COMPLETED", ledgerTransactionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.reviewReason").doesNotExist());
        assertThat(historyCount(transferId)).isEqualTo(1);
    }

    @Test
    void failsNotAcceptedTransferWhenLedgerRejectedWithTransferSideReason() throws Exception {
        UUID transferId = manualReview(NOT_ACCEPTED);
        givenLedger(transferId, "REJECTED", "INSUFFICIENT_BALANCE");

        // 원장 거절 기록은 다른 요청의 것일 수 있으므로 COMPLETED·PROCESSING은 허용하지 않습니다.
        expectMismatch(resolve(transferId, "COMPLETED", ledgerTransactionId));
        expectMismatch(resolve(transferId, "PROCESSING", null));
        assertUnchanged(transferId);

        // FAILED는 허용하되 실패 사유는 원장 거절 사유(INSUFFICIENT_BALANCE)가 아니라 송금 쪽에서 정한 NOT_PROCESSED입니다.
        resolve(transferId, "FAILED", ledgerTransactionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value("NOT_PROCESSED"))
                .andExpect(jsonPath("$.data.reviewReason").doesNotExist());
        assertThat(row(transferId).get("failure_reason")).isEqualTo("NOT_PROCESSED");
        mvc.perform(asMaster(get(history(transferId))))
                .andExpect(jsonPath("$.data[0].toStatus").value("FAILED"))
                .andExpect(jsonPath("$.data[0].ledgerTransactionId").value(ledgerTransactionId.toString()));
    }

    @Test
    void refusesToSettleWhenLedgerEntriesDifferFromTransfer() throws Exception {
        List<List<LedgerFeignApi.EntryResult>> differing = List.of(
                entries(sourceWalletId, targetWalletId, "KRW", 999L, "TRANSFER_OUT", "TRANSFER_IN"),
                entries(UUID.randomUUID(), targetWalletId, "KRW", 1000L, "TRANSFER_OUT", "TRANSFER_IN"),
                entries(sourceWalletId, UUID.randomUUID(), "KRW", 1000L, "TRANSFER_OUT", "TRANSFER_IN"),
                entries(sourceWalletId, targetWalletId, "JPY", 1000L, "TRANSFER_OUT", "TRANSFER_IN"),
                entries(sourceWalletId, targetWalletId, "KRW", 1000L, "PAYMENT_OUT", "PAYMENT_IN"),
                // 송금인·수취인 방향이 바뀐 경우
                entries(targetWalletId, sourceWalletId, "KRW", 1000L, "TRANSFER_OUT", "TRANSFER_IN"),
                // 항목이 빠지거나 더 있는 경우
                List.of(entry("USER", sourceWalletId, "KRW", "DEBIT", 1000L, "TRANSFER_OUT")),
                List.of(entry("USER", sourceWalletId, "KRW", "DEBIT", 1000L, "TRANSFER_OUT"),
                        entry("USER", targetWalletId, "KRW", "CREDIT", 1000L, "TRANSFER_IN"),
                        entry("USER", targetWalletId, "KRW", "CREDIT", 1000L, "TRANSFER_IN")),
                // 시스템 계정 항목
                List.of(entry("USER", sourceWalletId, "KRW", "DEBIT", 1000L, "TRANSFER_OUT"),
                        entry("SYSTEM", null, "KRW", "CREDIT", 1000L, "TRANSFER_IN")));

        for (String reviewReason : List.of(NOT_ACCEPTED, RETRY_EXHAUSTED)) {
            for (List<LedgerFeignApi.EntryResult> ledgerEntries : differing) {
                UUID transferId = manualReview(reviewReason);
                givenPostedLedger(transferId, ledgerEntries);

                expectMismatch(resolve(transferId, "COMPLETED", ledgerTransactionId));
                assertUnchanged(transferId);
            }
        }
    }

    @Test
    void keepsManualReviewWhenPostedLedgerHasNoReadableEntries() throws Exception {
        List<List<LedgerFeignApi.EntryResult>> unreadable = new ArrayList<>();
        unreadable.add(null);
        unreadable.add(List.of());
        unreadable.add(List.of(entry("USER", sourceWalletId, "KRW", "DEBIT", null, "TRANSFER_OUT"),
                entry("USER", targetWalletId, "KRW", "CREDIT", 1000L, "TRANSFER_IN")));
        unreadable.add(List.of(entry("USER", sourceWalletId, "KRW", "DEBIT", 1000L, null),
                entry("USER", targetWalletId, "KRW", "CREDIT", 1000L, "TRANSFER_IN")));

        for (List<LedgerFeignApi.EntryResult> ledgerEntries : unreadable) {
            UUID transferId = manualReview(RETRY_EXHAUSTED);
            givenPostedLedger(transferId, ledgerEntries);

            resolve(transferId, "COMPLETED", ledgerTransactionId)
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.error.code").value("COMMON_BAD_GATEWAY"));
            assertUnchanged(transferId);
        }
    }

    @Test
    void allowsRetryOfNotAcceptedTransferWithoutLedgerRecord() throws Exception {
        UUID transferId = manualReview(NOT_ACCEPTED);
        givenLedgerNotFound(transferId);

        resolve(transferId, "PROCESSING", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }

    @Test
    void rejectsTransferNotInManualReviewWithoutCallingLedger() throws Exception {
        UUID transferId = insertTransferRow(senderId, receiverId, "KRW", "PROCESSING", null, 1, null, CREATED);

        resolve(transferId, "COMPLETED", ledgerTransactionId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMMON_CONFLICT"));
        verify(ledgerFeignApi, never()).getTransaction(any(), any());
    }

    @Test
    void sameResolutionRetryReturnsExistingResultWithoutLedgerLookup() throws Exception {
        UUID transferId = manualReview(RETRY_EXHAUSTED);
        givenLedger(transferId, "REJECTED", "INSUFFICIENT_BALANCE");
        resolve(transferId, "FAILED", ledgerTransactionId).andExpect(status().isOk());

        resolve(transferId, "FAILED", ledgerTransactionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value("INSUFFICIENT_BALANCE"));
        assertThat(historyCount(transferId)).isEqualTo(1);
        verify(ledgerFeignApi, times(1)).getTransaction("TRANSFER", transferId);
    }

    @Test
    void concurrentSameResolutionThatLosesConditionalUpdateReturnsWinnerResult() throws Exception {
        UUID transferId = manualReview(RETRY_EXHAUSTED);
        givenLedgerPostedAfterCompetingResolution(transferId, masterId, "원장 POSTED 확인");

        resolve(transferId, "COMPLETED", ledgerTransactionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertThat(row(transferId).get("status")).isEqualTo("COMPLETED");
        assertThat(historyCount(transferId)).isEqualTo(1);
    }

    @Test
    void concurrentDifferentResolutionThatLosesConditionalUpdateIsConflict() throws Exception {
        UUID transferId = manualReview(RETRY_EXHAUSTED);
        givenLedgerPostedAfterCompetingResolution(transferId, UUID.randomUUID(), "원장 POSTED 확인");

        resolve(transferId, "COMPLETED", ledgerTransactionId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMMON_CONFLICT"));
        assertThat(row(transferId).get("status")).isEqualTo("COMPLETED");
        assertThat(historyCount(transferId)).isEqualTo(1);
    }

    @Test
    void differentResolutionAfterSettlementIsConflict() throws Exception {
        UUID transferId = manualReview(RETRY_EXHAUSTED);
        givenLedger(transferId, "POSTED", null);
        resolve(transferId, "COMPLETED", ledgerTransactionId).andExpect(status().isOk());

        expectConflict(asMaster(post(resolvePath(transferId))), body("COMPLETED", ledgerTransactionId, "다른 사유"));
        expectConflict(asMaster(post(resolvePath(transferId))), body("COMPLETED", UUID.randomUUID(), "원장 POSTED 확인"));
        expectConflict(asMaster(post(resolvePath(transferId))), body("FAILED", ledgerTransactionId, "원장 POSTED 확인"));
        expectConflict(post(resolvePath(transferId)).header(AuthHeaders.USER_ID, UUID.randomUUID().toString())
                .header(AuthHeaders.USER_ROLE, "MASTER"), body("COMPLETED", ledgerTransactionId, "원장 POSTED 확인"));

        assertThat(row(transferId).get("status")).isEqualTo("COMPLETED");
        assertThat(historyCount(transferId)).isEqualTo(1);
        verify(ledgerFeignApi, times(1)).getTransaction("TRANSFER", transferId);
    }

    @Test
    void rejectsNonMasterAndMissingTransfer() throws Exception {
        UUID transferId = manualReview(RETRY_EXHAUSTED);

        mvc.perform(asUser(post(resolvePath(transferId)), senderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("COMPLETED", ledgerTransactionId, "사유")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_FORBIDDEN"));
        mvc.perform(asUser(get(history(transferId)), senderId))
                .andExpect(status().isForbidden());
        resolve(UUID.randomUUID(), "COMPLETED", ledgerTransactionId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_NOT_FOUND"));
        mvc.perform(asMaster(get(history(UUID.randomUUID()))))
                .andExpect(status().isNotFound());
        mvc.perform(asMaster(get(history(transferId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        assertUnchanged(transferId);
    }

    @Test
    void rejectsInvalidRequests() throws Exception {
        UUID transferId = manualReview(RETRY_EXHAUSTED);

        expectInvalid(transferId, body("CANCELLED", null, "사유"));
        expectInvalid(transferId, body("MANUAL_REVIEW", null, "사유"));
        expectInvalid(transferId, body("COMPLETED", null, "사유"));
        expectInvalid(transferId, body("FAILED", null, "사유"));
        expectInvalid(transferId, body("PROCESSING", ledgerTransactionId, "사유"));
        expectInvalid(transferId, body("COMPLETED", ledgerTransactionId, " "));
        expectInvalid(transferId, body("COMPLETED", ledgerTransactionId, "가".repeat(256)));
        expectInvalid(transferId, """
                {"reason":"사유","ledgerTransactionId":"%s"}""".formatted(ledgerTransactionId));
        assertUnchanged(transferId);
        verify(ledgerFeignApi, never()).getTransaction(any(), any());
    }

    @Test
    void keepsManualReviewWhenLedgerLookupFails() throws Exception {
        UUID unavailable = manualReview(RETRY_EXHAUSTED);
        when(ledgerFeignApi.getTransaction("TRANSFER", unavailable))
                .thenThrow(lookupError(unavailable, 503, "COMMON_SERVICE_UNAVAILABLE"));
        UUID wrongNotFound = manualReview(RETRY_EXHAUSTED);
        when(ledgerFeignApi.getTransaction("TRANSFER", wrongNotFound))
                .thenThrow(lookupError(wrongNotFound, 404, "COMMON_NOT_FOUND"));
        UUID unreadable = manualReview(RETRY_EXHAUSTED);
        when(ledgerFeignApi.getTransaction("TRANSFER", unreadable)).thenReturn(ApiResponse.success(
                new LedgerFeignApi.TransactionResultResponse(ledgerTransactionId, "UNKNOWN", null, List.of())));

        resolve(unavailable, "COMPLETED", ledgerTransactionId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("COMMON_SERVICE_UNAVAILABLE"));
        resolve(wrongNotFound, "PROCESSING", null)
                .andExpect(status().isBadGateway());
        resolve(unreadable, "COMPLETED", ledgerTransactionId)
                .andExpect(status().isBadGateway());
        assertUnchanged(unavailable);
        assertUnchanged(wrongNotFound);
        assertUnchanged(unreadable);
    }

    private UUID manualReview(String reviewReason) {
        return insertTransferRow(senderId, receiverId, "KRW", "MANUAL_REVIEW", null, 5, reviewReason, CREATED);
    }

    /** 원장 결과를 정합니다. POSTED는 이 송금과 같은 항목을, REJECTED는 원장처럼 빈 항목을 돌려줍니다. */
    private void givenLedger(UUID transferId, String status, String rejectReason) {
        List<LedgerFeignApi.EntryResult> ledgerEntries = "POSTED".equals(status) ? transferEntries() : List.of();
        when(ledgerFeignApi.getTransaction("TRANSFER", transferId)).thenReturn(ApiResponse.success(
                new LedgerFeignApi.TransactionResultResponse(ledgerTransactionId, status, rejectReason, ledgerEntries)));
    }

    private void givenPostedLedger(UUID transferId, List<LedgerFeignApi.EntryResult> ledgerEntries) {
        when(ledgerFeignApi.getTransaction("TRANSFER", transferId)).thenReturn(ApiResponse.success(
                new LedgerFeignApi.TransactionResultResponse(ledgerTransactionId, "POSTED", null, ledgerEntries)));
    }

    /** manualReview로 넣은 송금(KRW 1000, 이 테스트의 송금인·수취인 지갑)의 원장 항목입니다. */
    private List<LedgerFeignApi.EntryResult> transferEntries() {
        return entries(sourceWalletId, targetWalletId, "KRW", 1000L, "TRANSFER_OUT", "TRANSFER_IN");
    }

    private static List<LedgerFeignApi.EntryResult> entries(UUID debitWalletId, UUID creditWalletId, String currency,
            Long amount, String debitType, String creditType) {
        return List.of(entry("USER", debitWalletId, currency, "DEBIT", amount, debitType),
                entry("USER", creditWalletId, currency, "CREDIT", amount, creditType));
    }

    private static LedgerFeignApi.EntryResult entry(String ownerType, UUID walletId, String currency, String direction,
            Long amount, String entryType) {
        String accountType = "USER".equals(ownerType) ? "WALLET" : "FX_POSITION";
        return new LedgerFeignApi.EntryResult(ownerType, walletId, accountType, currency, direction, amount, entryType);
    }

    /**
     * 동시 요청 경합을 재현합니다. 서비스는 MANUAL_REVIEW 확인 → 원장 조회 → 조건부 UPDATE 순서라, 원장 조회 중에
     * 다른 요청이 먼저 확정(COMPLETED + 운영자 이력)한 것처럼 DB를 바꿔 두면 이 요청의 조건부 UPDATE는 0행이 됩니다.
     */
    private void givenLedgerPostedAfterCompetingResolution(UUID transferId, UUID winnerId, String winnerReason) {
        when(ledgerFeignApi.getTransaction("TRANSFER", transferId)).thenAnswer(invocation -> {
            Instant now = Instant.now();
            jdbcTemplate.update("""
                    update transfer set status = 'COMPLETED', review_reason = null, updated_at = ? where id = ?""",
                    Timestamp.from(now), transferId);
            jdbcTemplate.update("""
                    insert into transfer_status_history
                        (id, transfer_id, from_status, to_status, reason, actor_type, actor_id,
                         ledger_transaction_id, created_at)
                    values (?, ?, 'MANUAL_REVIEW', 'COMPLETED', ?, 'OPERATOR', ?, ?, ?)""",
                    UUID.randomUUID(), transferId, winnerReason, winnerId, ledgerTransactionId, Timestamp.from(now));
            return ApiResponse.success(
                    new LedgerFeignApi.TransactionResultResponse(ledgerTransactionId, "POSTED", null, transferEntries()));
        });
    }

    private void givenLedgerNotFound(UUID transferId) {
        when(ledgerFeignApi.getTransaction("TRANSFER", transferId))
                .thenThrow(lookupError(transferId, 404, "LEDGER_TRANSACTION_NOT_FOUND"));
    }

    private static FeignException lookupError(UUID transferId, int status, String code) {
        return feignError("LedgerFeignApi#getTransaction(String,UUID)", Request.HttpMethod.GET,
                "http://ledger-service/internal/v1/ledger/transactions?referenceType=TRANSFER&referenceId=" + transferId,
                status, errorBody(code));
    }

    /** MASTER로 확정 요청을 보냅니다. 사유는 targetStatus별 고정값이라 같은 호출을 반복하면 같은 요청이 됩니다. */
    private ResultActions resolve(UUID transferId, String targetStatus, UUID ledgerId) throws Exception {
        String reason = "COMPLETED".equals(targetStatus) ? "원장 POSTED 확인" : "원장 결과 확인";
        return mvc.perform(asMaster(post(resolvePath(transferId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(targetStatus, ledgerId, reason)));
    }

    private void expectInvalid(UUID transferId, String body) throws Exception {
        mvc.perform(asMaster(post(resolvePath(transferId))).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    /** 확정 요청이 409 COMMON_CONFLICT인지 확인합니다. */
    private void expectConflict(MockHttpServletRequestBuilder builder, String body) throws Exception {
        mvc.perform(builder.contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMMON_CONFLICT"));
    }

    private static void expectMismatch(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_RESOLUTION_MISMATCH"));
    }

    private void assertUnchanged(UUID transferId) {
        Map<String, Object> row = row(transferId);
        assertThat(row.get("status")).isEqualTo("MANUAL_REVIEW");
        assertThat(row.get("attempt_count")).isEqualTo(5);
        assertThat(historyCount(transferId)).isZero();
    }

    // 테스트는 한 트랜잭션이라 커밋 때의 flush가 없으므로, SQL로 세기 전에 저장 대기 중인 이력을 반영합니다.
    private long historyCount(UUID transferId) {
        entityManager.flush();
        return jdbcTemplate.queryForObject(
                "select count(*) from transfer_status_history where transfer_id = ?", Long.class, transferId);
    }

    /** 확정 요청 본문 JSON을 만듭니다. ledgerId가 null이면 null 값으로 보냅니다. */
    private static String body(String targetStatus, UUID ledgerId, String reason) {
        String ledger = ledgerId == null ? "null" : "\"" + ledgerId + "\"";
        return """
                {"targetStatus":"%s","reason":"%s","ledgerTransactionId":%s}""".formatted(targetStatus, reason, ledger);
    }

    private MockHttpServletRequestBuilder asMaster(MockHttpServletRequestBuilder builder) {
        return builder.header(AuthHeaders.USER_ID, masterId.toString()).header(AuthHeaders.USER_ROLE, "MASTER");
    }

    private static String resolvePath(UUID transferId) {
        return "/api/v1/admin/transfers/" + transferId + "/resolve";
    }

    private static String history(UUID transferId) {
        return "/api/v1/admin/transfers/" + transferId + "/history";
    }
}
