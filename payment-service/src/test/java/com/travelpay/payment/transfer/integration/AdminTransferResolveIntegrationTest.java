package com.travelpay.payment.transfer.integration;

import java.time.Instant;
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
    void refusesToSettleNotAcceptedTransferThatHasLedgerRecord() throws Exception {
        UUID transferId = manualReview(NOT_ACCEPTED);
        givenLedger(transferId, "POSTED", null);

        expectMismatch(resolve(transferId, "COMPLETED", ledgerTransactionId));
        assertThat(row(transferId).get("review_reason")).isEqualTo(NOT_ACCEPTED);
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
    void secondResolutionIsConflict() throws Exception {
        UUID transferId = manualReview(RETRY_EXHAUSTED);
        givenLedger(transferId, "POSTED", null);
        resolve(transferId, "COMPLETED", ledgerTransactionId).andExpect(status().isOk());

        resolve(transferId, "COMPLETED", ledgerTransactionId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMMON_CONFLICT"));
        assertThat(historyCount(transferId)).isEqualTo(1);
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
                new LedgerFeignApi.TransactionResultResponse(ledgerTransactionId, "UNKNOWN", null)));

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

    private void givenLedger(UUID transferId, String status, String rejectReason) {
        when(ledgerFeignApi.getTransaction("TRANSFER", transferId)).thenReturn(ApiResponse.success(
                new LedgerFeignApi.TransactionResultResponse(ledgerTransactionId, status, rejectReason)));
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
