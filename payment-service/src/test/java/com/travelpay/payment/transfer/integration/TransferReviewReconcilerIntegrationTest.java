package com.travelpay.payment.transfer.integration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi;
import com.travelpay.payment.transfer.application.TransferReviewReconciler;
import com.travelpay.payment.transfer.client.TransferLedgerReader;
import com.travelpay.payment.transfer.domain.TransferRepository;
import com.travelpay.payment.transfer.domain.TransferStatusHistoryRepository;
import com.travelpay.payment.transfer.support.TransferApiIntegrationSupport;
import feign.FeignException;
import feign.Request;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MANUAL_REVIEW 자동 대사({@link TransferReviewReconciler})를 실제 서비스·PostgreSQL로 확인합니다. 원장 결과 조회(Feign)만 Mock입니다.
 * 원장 결과가 확정적인 송금만 확정되고 SYSTEM 이력이 남는지, 나머지는 운영자에게 그대로 남는지 확인합니다.
 * 커서 상태를 테스트마다 새로 시작하도록 대사 객체를 직접 만듭니다.
 */
class TransferReviewReconcilerIntegrationTest extends TransferApiIntegrationSupport {

    private static final String RETRY_EXHAUSTED = "RETRY_EXHAUSTED";
    private static final String NOT_ACCEPTED = "LEDGER_REQUEST_NOT_ACCEPTED";

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransferStatusHistoryRepository historyRepository;

    @Autowired
    private TransferLedgerReader ledgerReader;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final UUID ledgerTransactionId = UUID.randomUUID();
    private final UUID masterId = UUID.randomUUID();
    private SimpleMeterRegistry meterRegistry;
    private TransferReviewReconciler reconciler;
    private Instant now;
    private int sequence;

    @BeforeEach
    void setUpReconciler() {
        meterRegistry = new SimpleMeterRegistry();
        reconciler = new TransferReviewReconciler(transferRepository, historyRepository, ledgerReader,
                transactionManager, meterRegistry);
        now = Instant.now();
        // 다른 테스트가 남긴 송금이 있어도 원장 기록 없음으로 보고 건드리지 않게 합니다.
        when(ledgerFeignApi.getTransaction(eq("TRANSFER"), any())).thenThrow(notFound());
    }

    @Test
    void completesPostedTransferWithMatchingEntriesAndRecordsSystemHistory() throws Exception {
        UUID transferId = manualReview(RETRY_EXHAUSTED);
        givenLedger(transferId, "POSTED", null, transferEntries());

        assertThat(reconciler.reconcile(now, 10)).isEqualTo(1);

        Map<String, Object> row = row(transferId);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("review_reason")).isNull();
        assertThat(row.get("attempt_count")).isEqualTo(5);
        entityManager.flush();
        mvc.perform(asMaster(get("/api/v1/admin/transfers/" + transferId + "/history")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fromStatus").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.data[0].toStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data[0].reason").value("원장 결과 자동 대사"))
                .andExpect(jsonPath("$.data[0].actorType").value("SYSTEM"))
                .andExpect(jsonPath("$.data[0].actorId").doesNotExist())
                .andExpect(jsonPath("$.data[0].ledgerTransactionId").value(ledgerTransactionId.toString()));
        assertThat(autoResolved("COMPLETED")).isEqualTo(1.0);
    }

    @Test
    void failsRejectedTransfersWithSameFailureReasonAsOperatorResolution() {
        UUID insufficient = manualReview(RETRY_EXHAUSTED);
        givenLedger(insufficient, "REJECTED", "INSUFFICIENT_BALANCE", List.of());
        UUID suspended = manualReview(RETRY_EXHAUSTED);
        givenLedger(suspended, "REJECTED", "WALLET_SUSPENDED", List.of());
        UUID notAccepted = manualReview(NOT_ACCEPTED);
        givenLedger(notAccepted, "REJECTED", "INSUFFICIENT_BALANCE", List.of());

        assertThat(reconciler.reconcile(now, 10)).isEqualTo(3);

        assertThat(row(insufficient).get("failure_reason")).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(row(suspended).get("failure_reason")).isEqualTo("ACCOUNT_UNAVAILABLE");
        // 원장이 요청을 받지 않은 송금은 원장 거절 사유 대신 NOT_PROCESSED입니다.
        assertThat(row(notAccepted).get("failure_reason")).isEqualTo("NOT_PROCESSED");
        for (UUID transferId : List.of(insufficient, suspended, notAccepted)) {
            assertThat(row(transferId).get("status")).isEqualTo("FAILED");
            assertThat(historyRows(transferId)).containsExactly("SYSTEM");
        }
        assertThat(autoResolved("FAILED")).isEqualTo(3.0);
    }

    @Test
    void leavesTransfersWithoutLedgerRecordOrWithDifferentEntriesToOperator() {
        UUID missing = manualReview(RETRY_EXHAUSTED);
        UUID differentEntries = manualReview(NOT_ACCEPTED);
        givenLedger(differentEntries, "POSTED", null,
                entries(sourceWalletId, targetWalletId, "KRW", 999L));

        assertThat(reconciler.reconcile(now, 10)).isZero();

        assertUnchanged(missing);
        assertUnchanged(differentEntries);
    }

    @Test
    void skipsTransfersThatJustEnteredManualReview() {
        UUID recent = insertTransferRow(senderId, receiverId, "KRW", "MANUAL_REVIEW", null, 5, RETRY_EXHAUSTED,
                now.minusSeconds(30));
        givenLedger(recent, "POSTED", null, transferEntries());

        assertThat(reconciler.reconcile(now, 10)).isZero();

        assertUnchanged(recent);
        verify(ledgerFeignApi, never()).getTransaction("TRANSFER", recent);
    }

    @Test
    void doesNotTouchTransfersOutsideManualReview() {
        UUID processing = insertTransferRow(senderId, receiverId, "KRW", "PROCESSING", null, 1, null, past());
        UUID completed = insertTransferRow(senderId, receiverId, "KRW", "COMPLETED", null, 1, null, past());

        reconciler.reconcile(now, 10);

        verify(ledgerFeignApi, never()).getTransaction("TRANSFER", processing);
        verify(ledgerFeignApi, never()).getTransaction("TRANSFER", completed);
    }

    /** 확정하지 못하고 남는 송금이 앞에 있어도, 다음 주기는 커서 다음부터 봐서 뒤의 송금을 확정합니다. */
    @Test
    void continuesAfterUnresolvedTransfersInNextCycle() {
        UUID missing1 = manualReview(RETRY_EXHAUSTED);
        UUID missing2 = manualReview(RETRY_EXHAUSTED);
        UUID posted = manualReview(RETRY_EXHAUSTED);
        givenLedger(posted, "POSTED", null, transferEntries());

        assertThat(reconciler.reconcile(now, 2)).isZero();
        assertThat(reconciler.reconcile(now, 2)).isEqualTo(1);

        assertThat(row(posted).get("status")).isEqualTo("COMPLETED");
        assertUnchanged(missing1);
        assertUnchanged(missing2);
    }

    /** 원장 기록 없음은 조회 실패가 아니므로, 연달아 있어도 주기를 멈추지 않고 뒤의 송금을 같은 주기에 확정합니다. */
    @Test
    void ledgerRecordMissingDoesNotCountAsConsecutiveFailure() {
        List<UUID> missing = new ArrayList<>();
        // 연속 실패 한도(3)보다 많게 둡니다.
        for (int i = 0; i < 4; i++) {
            missing.add(manualReview(RETRY_EXHAUSTED));
        }
        UUID posted = manualReview(RETRY_EXHAUSTED);
        givenLedger(posted, "POSTED", null, transferEntries());

        assertThat(reconciler.reconcile(now, 10)).isEqualTo(1);

        assertThat(row(posted).get("status")).isEqualTo("COMPLETED");
        missing.forEach(this::assertUnchanged);
    }

    /** 원장 조회가 연달아 실패하면 남은 송금은 다음 주기로 넘기고, 다음 주기는 같은 위치부터 다시 봅니다. */
    @Test
    void stopsCycleAfterConsecutiveLedgerFailuresAndRetriesFromSamePosition() {
        UUID first = manualReview(RETRY_EXHAUSTED);
        UUID second = manualReview(RETRY_EXHAUSTED);
        UUID third = manualReview(RETRY_EXHAUSTED);
        UUID fourth = manualReview(RETRY_EXHAUSTED);
        for (UUID transferId : List.of(first, second, third)) {
            doThrow(ledgerError(503, "COMMON_SERVICE_UNAVAILABLE"))
                    .doReturn(ApiResponse.success(new LedgerFeignApi.TransactionResultResponse(
                            ledgerTransactionId, "POSTED", null, transferEntries())))
                    .when(ledgerFeignApi).getTransaction("TRANSFER", transferId);
        }
        givenLedger(fourth, "POSTED", null, transferEntries());

        assertThat(reconciler.reconcile(now, 10)).isZero();
        assertUnchanged(fourth);
        verify(ledgerFeignApi, never()).getTransaction("TRANSFER", fourth);

        assertThat(reconciler.reconcile(now, 10)).isEqualTo(4);
        for (UUID transferId : List.of(first, second, third, fourth)) {
            assertThat(row(transferId).get("status")).isEqualTo("COMPLETED");
        }
    }

    /** 자동 대사로 확정된 송금에 운영자 확정이 오면 운영자 확정 이력이 없으므로 409이고 상태는 그대로입니다. */
    @Test
    void operatorResolutionAfterAutoResolutionIsConflict() throws Exception {
        UUID transferId = manualReview(RETRY_EXHAUSTED);
        givenLedger(transferId, "POSTED", null, transferEntries());
        reconciler.reconcile(now, 10);
        entityManager.flush();

        mvc.perform(asMaster(post("/api/v1/admin/transfers/" + transferId + "/resolve"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetStatus":"COMPLETED","reason":"원장 POSTED 확인","ledgerTransactionId":"%s"}"""
                                .formatted(ledgerTransactionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMMON_CONFLICT"));
        assertThat(row(transferId).get("status")).isEqualTo("COMPLETED");
        assertThat(historyRows(transferId)).containsExactly("SYSTEM");
    }

    /** DB 제약: SYSTEM 이력은 actor_id가 없어야 하고, 운영자 이력은 있어야 합니다. */
    @Test
    void historyActorIdMustMatchActorType() {
        UUID transferId = manualReview(RETRY_EXHAUSTED);

        assertThat(insertHistory(transferId, "SYSTEM", UUID.randomUUID())).isFalse();
        assertThat(insertHistory(transferId, "OPERATOR", null)).isFalse();
        assertThat(insertHistory(transferId, "SYSTEM", null)).isTrue();
    }

    /** 생성·수정 시각이 1분보다 오래된 MANUAL_REVIEW 송금을 넣습니다. 넣는 순서대로 수정 시각이 앞섭니다. */
    private UUID manualReview(String reviewReason) {
        return insertTransferRow(senderId, receiverId, "KRW", "MANUAL_REVIEW", null, 5, reviewReason, past());
    }

    private Instant past() {
        return now.minus(Duration.ofMinutes(10)).plusMillis(sequence++);
    }

    private void givenLedger(UUID transferId, String status, String rejectReason,
            List<LedgerFeignApi.EntryResult> ledgerEntries) {
        doReturn(ApiResponse.success(
                new LedgerFeignApi.TransactionResultResponse(ledgerTransactionId, status, rejectReason, ledgerEntries)))
                .when(ledgerFeignApi).getTransaction("TRANSFER", transferId);
    }

    /** manualReview로 넣은 송금(KRW 1000, 이 테스트의 송금인·수취인 지갑)의 원장 항목입니다. */
    private List<LedgerFeignApi.EntryResult> transferEntries() {
        return entries(sourceWalletId, targetWalletId, "KRW", 1000L);
    }

    private static List<LedgerFeignApi.EntryResult> entries(UUID debitWalletId, UUID creditWalletId, String currency,
            Long amount) {
        return List.of(
                new LedgerFeignApi.EntryResult("USER", debitWalletId, "WALLET", currency, "DEBIT", amount, "TRANSFER_OUT"),
                new LedgerFeignApi.EntryResult("USER", creditWalletId, "WALLET", currency, "CREDIT", amount, "TRANSFER_IN"));
    }

    private static FeignException notFound() {
        return feignError("LedgerFeignApi#getTransaction(String,UUID)", Request.HttpMethod.GET,
                "http://ledger-service/internal/v1/ledger/transactions", 404, errorBody("LEDGER_TRANSACTION_NOT_FOUND"));
    }

    private void assertUnchanged(UUID transferId) {
        Map<String, Object> row = row(transferId);
        assertThat(row.get("status")).isEqualTo("MANUAL_REVIEW");
        assertThat(row.get("attempt_count")).isEqualTo(5);
        assertThat(historyRows(transferId)).isEmpty();
    }

    // 테스트는 한 트랜잭션이라 커밋 때의 flush가 없으므로, SQL로 읽기 전에 저장 대기 중인 이력을 반영합니다.
    private List<String> historyRows(UUID transferId) {
        entityManager.flush();
        return jdbcTemplate.queryForList(
                "select actor_type from transfer_status_history where transfer_id = ?", String.class, transferId);
    }

    /** 이력 행을 SQL로 넣어 보고 제약을 통과하면 true입니다. 실패해도 테스트 트랜잭션이 깨지지 않도록 savepoint 안에서 넣습니다. */
    private boolean insertHistory(UUID transferId, String actorType, UUID actorId) {
        return Boolean.TRUE.equals(jdbcTemplate.execute((Connection connection) -> {
            Savepoint savepoint = connection.setSavepoint();
            try (var statement = connection.prepareStatement("""
                    insert into transfer_status_history
                        (id, transfer_id, from_status, to_status, reason, actor_type, actor_id, created_at)
                    values (?, ?, 'MANUAL_REVIEW', 'COMPLETED', 'test', ?, ?, now())""")) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, transferId);
                statement.setString(3, actorType);
                statement.setObject(4, actorId);
                statement.executeUpdate();
                connection.releaseSavepoint(savepoint);
                return true;
            } catch (SQLException exception) {
                connection.rollback(savepoint);
                return false;
            }
        }));
    }

    private double autoResolved(String status) {
        return meterRegistry.get("transfer.manual_review.auto_resolved").tag("status", status).counter().count();
    }

    private MockHttpServletRequestBuilder asMaster(MockHttpServletRequestBuilder builder) {
        return builder.header(AuthHeaders.USER_ID, masterId.toString()).header(AuthHeaders.USER_ROLE, "MASTER");
    }
}
