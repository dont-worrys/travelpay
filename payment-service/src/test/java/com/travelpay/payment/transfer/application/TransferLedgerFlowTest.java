package com.travelpay.payment.transfer.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.payment.transfer.client.LedgerTransactionRequest;
import com.travelpay.payment.transfer.client.LedgerTransactionResponse;
import com.travelpay.payment.transfer.client.LedgerTransactionResponse.RejectReason;
import com.travelpay.payment.transfer.client.TransferLedgerClient;
import com.travelpay.payment.transfer.client.TransferLedgerException;
import com.travelpay.payment.transfer.client.TransferUserReader;
import com.travelpay.payment.transfer.client.TransferWalletReader;
import com.travelpay.payment.transfer.client.UserRecipientResponse;
import com.travelpay.payment.transfer.domain.Transfer;
import com.travelpay.payment.transfer.domain.TransferErrorCode;
import com.travelpay.payment.transfer.domain.TransferFailureReason;
import com.travelpay.payment.transfer.domain.TransferRepository;
import com.travelpay.payment.transfer.domain.TransferReviewReason;
import com.travelpay.payment.transfer.domain.TransferStatus;
import com.travelpay.payment.transfer.dto.TransferCreateRequest;
import com.travelpay.payment.transfer.dto.TransferResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 원장 거래 등록 구현이 있을 때의 송금 흐름을 검증합니다.
 * 실제 원장 대신 지갑별 잔액과 처리한 reference를 기억하는 테스트용 원장을 사용하며, 실제 잔액은 변경하지 않습니다.
 * 테스트용 원장의 reference 중복 처리 방지·차감과 증가의 원자성은 원장 구현체가 보장해야 할 계약을 흉내 낸 것으로,
 * 이 테스트는 송금 서비스가 재전송 때 같은 송금 ID·reference·내용을 보내는지를 확인합니다.
 */
class TransferLedgerFlowTest {

    private static final UUID SENDER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID RECEIVER_ID = UUID.fromString("4c6a0178-266b-4f1e-b811-a2767026be70");
    private static final UUID SOURCE_WALLET_ID = UUID.fromString("0f8e6d4c-2b1a-4f9e-8d7c-6b5a4f3e2d1c");
    private static final UUID TARGET_WALLET_ID = UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    private static final String KEY = "c2f1d0e9-transfer-key";
    private static final TransferCreateRequest REQUEST = new TransferCreateRequest(RECEIVER_ID, 3_000L, "KRW", "저녁값");

    private final Map<UUID, Transfer> store = new LinkedHashMap<>();
    private TransferRepository transferRepository;
    private FakeLedger ledger;
    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferRepository = mock(TransferRepository.class);
        givenInMemoryRepository();
        ledger = new FakeLedger(10_000L, 0L);
        transferService = newServiceWith(ledger);
    }

    @Test
    void completesTransferWithDebitAndCreditInOneLedgerTransaction() {
        TransferResponse response = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.COMPLETED, response.status());
        assertNull(onlyTransfer().getFailureReason());
        assertEquals(7_000L, ledger.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, ledger.balance(TARGET_WALLET_ID));
        assertEquals(List.of(new LedgerTransactionRequest("TRANSFER", response.transferId(), SENDER_ID, List.of(
                new LedgerTransactionRequest.Entry("USER", SOURCE_WALLET_ID, "KRW", "DEBIT", 3_000L, "TRANSFER_OUT"),
                new LedgerTransactionRequest.Entry("USER", TARGET_WALLET_ID, "KRW", "CREDIT", 3_000L, "TRANSFER_IN")
        ))), ledger.requests);
    }

    @Test
    void savesProcessingBeforeCallingLedger() {
        ledger.onRegister = request -> {
            assertEquals(TransferStatus.PROCESSING, store.get(request.referenceId()).getStatus());
            return ledger.apply(request);
        };

        transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(1, ledger.requests.size());
    }

    @Test
    void failsWithoutChangingBalancesWhenSenderBalanceIsInsufficient() {
        ledger = new FakeLedger(1_000L, 0L);
        transferService = newServiceWith(ledger);

        var exception = assertThrows(BusinessException.class, () -> transferService.request(SENDER_ID, KEY, REQUEST));

        assertSame(TransferErrorCode.INSUFFICIENT_BALANCE, exception.getErrorCode());
        assertEquals(TransferStatus.FAILED, onlyTransfer().getStatus());
        assertEquals(TransferFailureReason.INSUFFICIENT_BALANCE, onlyTransfer().getFailureReason());
        assertEquals(1_000L, ledger.balance(SOURCE_WALLET_ID));
        assertEquals(0L, ledger.balance(TARGET_WALLET_ID));
    }

    /** 계정 정지·계정 없음 거절은 같은 오류(TRANSFER_ACCOUNT_UNAVAILABLE)로 응답합니다. */
    @ParameterizedTest
    @EnumSource(value = RejectReason.class, names = {"WALLET_SUSPENDED", "ACCOUNT_NOT_FOUND"})
    void failsWithAccountUnavailableWhenLedgerRejectsAccount(RejectReason reason) {
        ledger.rejectWith = reason;

        var exception = assertThrows(BusinessException.class, () -> transferService.request(SENDER_ID, KEY, REQUEST));

        assertSame(TransferErrorCode.ACCOUNT_UNAVAILABLE, exception.getErrorCode());
        assertEquals(TransferStatus.FAILED, onlyTransfer().getStatus());
        assertEquals(TransferFailureReason.ACCOUNT_UNAVAILABLE, onlyTransfer().getFailureReason());
        assertEquals(10_000L, ledger.balance(SOURCE_WALLET_ID));
        assertEquals(0L, ledger.balance(TARGET_WALLET_ID));
    }

    @Test
    void returnsCompletedTransferForRetryWithoutCallingLedgerAgain() {
        TransferResponse first = transferService.request(SENDER_ID, KEY, REQUEST);

        TransferResponse retry = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(first.transferId(), retry.transferId());
        assertEquals(TransferStatus.COMPLETED, retry.status());
        assertEquals(1, ledger.requests.size());
        assertEquals(7_000L, ledger.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, ledger.balance(TARGET_WALLET_ID));
    }

    @Test
    void returnsSameInsufficientBalanceErrorForRetryWithoutCallingLedgerAgain() {
        ledger = new FakeLedger(1_000L, 0L);
        transferService = newServiceWith(ledger);
        assertThrows(BusinessException.class, () -> transferService.request(SENDER_ID, KEY, REQUEST));

        var retry = assertThrows(BusinessException.class, () -> transferService.request(SENDER_ID, KEY, REQUEST));

        assertSame(TransferErrorCode.INSUFFICIENT_BALANCE, retry.getErrorCode());
        assertEquals(1, ledger.requests.size());
    }

    @Test
    void returnsSameAccountUnavailableErrorForRetryWithoutCallingLedgerAgain() {
        ledger.rejectWith = RejectReason.WALLET_SUSPENDED;
        assertThrows(BusinessException.class, () -> transferService.request(SENDER_ID, KEY, REQUEST));

        var retry = assertThrows(BusinessException.class, () -> transferService.request(SENDER_ID, KEY, REQUEST));

        assertSame(TransferErrorCode.ACCOUNT_UNAVAILABLE, retry.getErrorCode());
        assertEquals(1, ledger.requests.size());
    }

    @Test
    void keepsProcessingWhenLedgerTimesOut() {
        ledger.onRegister = request -> {
            throw new IllegalStateException("read timeout");
        };

        TransferResponse response = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.PROCESSING, response.status());
        assertEquals(TransferStatus.PROCESSING, onlyTransfer().getStatus());
        verify(transferRepository, never()).finishProcessing(any(), any(), any(), any());
        assertEquals(10_000L, ledger.balance(SOURCE_WALLET_ID));
    }

    /** 어댑터가 결과 모름(타임아웃·연결 실패·5xx)으로 알리면 PROCESSING(202)으로 두고 시도 횟수를 기록하며, 복구가 같은 reference로 완료합니다. */
    @Test
    void keepsProcessingWhenAdapterReportsResultUnknownAndRecoveryCompletesIt() {
        ledger.onRegister = request -> {
            throw TransferLedgerException.resultUnknown("원장 거래 등록 실패 status=-1 code=-", null);
        };
        TransferResponse first = transferService.request(SENDER_ID, KEY, REQUEST);
        assertEquals(1, onlyTransfer().getAttemptCount());
        ledger.onRegister = ledger::apply;

        int settled = transferService.recoverDue(farFuture(), 10);

        assertEquals(TransferStatus.PROCESSING, first.status());
        assertEquals(1, settled);
        assertEquals(TransferStatus.COMPLETED, onlyTransfer().getStatus());
        assertEquals(7_000L, ledger.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, ledger.balance(TARGET_WALLET_ID));
    }

    /** 원장이 요청을 받아들이지 않으면(409·400 등) FAILED로 단정하지 않고, 재시도 없이 MANUAL_REVIEW로 둡니다. */
    @Test
    void movesToManualReviewWithoutFailingWhenLedgerDoesNotAcceptRequest() {
        ledger.onRegister = request -> {
            throw TransferLedgerException.requestNotAccepted(
                    "원장 거래 등록 실패 status=409 code=LEDGER_REFERENCE_CONFLICT", null);
        };

        TransferResponse response = transferService.request(SENDER_ID, KEY, REQUEST);
        int settled = transferService.recoverDue(farFuture(), 10);
        TransferResponse retry = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.MANUAL_REVIEW, response.status());
        assertEquals(TransferStatus.MANUAL_REVIEW, retry.status());
        assertEquals(TransferReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, onlyTransfer().getReviewReason());
        assertNull(onlyTransfer().getFailureReason());
        assertEquals(0, settled);
        assertEquals(1, ledger.requests.size());
        verify(transferRepository, never()).finishProcessing(any(), any(), any(), any());
        assertEquals(10_000L, ledger.balance(SOURCE_WALLET_ID));
    }

    /** 응답이 없거나 거절 사유가 빠지는 등 결과를 해석할 수 없으면 실패로 단정하지 않습니다. */
    @Test
    void keepsProcessingWhenLedgerResultIsUnknown() {
        ledger.onRegister = request -> null;
        TransferResponse noResult = transferService.request(SENDER_ID, KEY, REQUEST);
        ledger.onRegister = request -> LedgerTransactionResponse.rejected(UUID.randomUUID(), null);
        TransferResponse noReason = transferService.request(SENDER_ID, "other-key", REQUEST);

        assertEquals(TransferStatus.PROCESSING, noResult.status());
        assertEquals(TransferStatus.PROCESSING, noReason.status());
        verify(transferRepository, never()).finishProcessing(any(), any(), any(), any());
    }

    /** 같은 키 재요청은 처리 중 송금이라도 원장을 다시 호출하지 않습니다. 재전송은 복구만 맡습니다. */
    @Test
    void retryOfProcessingTransferRespondsWithCurrentStateWithoutCallingLedger() {
        ledger.onRegister = request -> {
            throw new IllegalStateException("connect timeout");
        };
        TransferResponse first = transferService.request(SENDER_ID, KEY, REQUEST);
        ledger.onRegister = ledger::apply;

        TransferResponse retry = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(first.transferId(), retry.transferId());
        assertEquals(TransferStatus.PROCESSING, retry.status());
        assertEquals(1, ledger.requests.size());
        assertEquals(1, onlyTransfer().getAttemptCount());
    }

    /** 처음 요청은 원장에서 처리됐지만 응답만 유실된 경우, 복구가 같은 reference·내용을 다시 보내도 잔액은 한 번만 바뀝니다. */
    @Test
    void recoveryResendsSameReferenceWithoutMovingBalanceTwiceWhenResponseWasLost() {
        ledger.onRegister = request -> {
            ledger.apply(request);
            throw new IllegalStateException("read timeout");
        };
        TransferResponse first = transferService.request(SENDER_ID, KEY, REQUEST);
        assertEquals(TransferStatus.PROCESSING, first.status());
        ledger.onRegister = ledger::apply;

        transferService.recoverDue(farFuture(), 10);

        assertEquals(TransferStatus.COMPLETED, onlyTransfer().getStatus());
        assertEquals(7_000L, ledger.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, ledger.balance(TARGET_WALLET_ID));
        assertEquals(2, ledger.requests.size());
        assertEquals(first.transferId(), ledger.requests.get(1).referenceId());
        assertEquals(ledger.requests.get(0), ledger.requests.get(1));
    }

    /** 원장 처리는 성공했지만 결과 반영(DB) 중 오류가 난 경우입니다. 500 대신 처리 중으로 응답하고 복구로 반영합니다. */
    @Test
    void keepsProcessingWhenResultCannotBeSavedAndRecoveryCompletesWithoutMovingBalanceTwice() {
        when(transferRepository.finishProcessing(any(UUID.class), any(TransferStatus.class), any(), any()))
                .thenThrow(new DataIntegrityViolationException("ck_transfer_status"))
                .thenAnswer(invocation -> {
                    ReflectionTestUtils.setField(store.get(invocation.<UUID>getArgument(0)), "status",
                            invocation.getArgument(1));
                    return 1;
                });

        TransferResponse first = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.PROCESSING, first.status());
        assertEquals(TransferStatus.PROCESSING, onlyTransfer().getStatus());
        assertEquals(7_000L, ledger.balance(SOURCE_WALLET_ID));

        transferService.recoverDue(farFuture(), 10);

        assertEquals(TransferStatus.COMPLETED, onlyTransfer().getStatus());
        assertEquals(7_000L, ledger.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, ledger.balance(TARGET_WALLET_ID));
        assertEquals(2, ledger.requests.size());
    }

    @Test
    void rejectsRetryWithDifferentRequestWithoutCallingLedger() {
        ledger.onRegister = request -> null;
        transferService.request(SENDER_ID, KEY, REQUEST);

        var exception = assertThrows(BusinessException.class, () -> transferService.request(SENDER_ID, KEY,
                new TransferCreateRequest(RECEIVER_ID, 5_000L, "KRW", "저녁값")));

        assertSame(TransferErrorCode.IDEMPOTENCY_KEY_REUSED, exception.getErrorCode());
        assertEquals(1, ledger.requests.size());
    }

    /** 같은 키의 첫 요청이 저장된 뒤 원장 처리 중일 때 들어온 요청은 UNIQUE 위반 후 저장된 송금의 현재 상태로 응답하고, 원장은 호출하지 않습니다. */
    @Test
    void concurrentDuplicateRespondsWithStoredTransferWithoutCallingLedger() {
        Transfer inFlight = savedProcessingTransfer();
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY))
                .thenReturn(Optional.empty())
                .thenAnswer(invocation -> Optional.of(inFlight));

        TransferResponse response = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(inFlight.getId(), response.transferId());
        assertEquals(TransferStatus.PROCESSING, response.status());
        assertEquals(1, store.size());
        assertTrue(ledger.requests.isEmpty());
    }

    /** 다른 곳에서 먼저 결과를 반영해 조건부 UPDATE가 0행이면, 다시 읽은 상태를 응답합니다. */
    @Test
    void returnsStateFinishedElsewhereWhenConditionalUpdateMisses() {
        ledger.onRegister = request -> {
            LedgerTransactionResponse result = ledger.apply(request);
            ReflectionTestUtils.setField(store.get(request.referenceId()), "status", TransferStatus.COMPLETED);
            return result;
        };

        TransferResponse response = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.COMPLETED, response.status());
        verify(transferRepository).finishProcessing(eq(response.transferId()), eq(TransferStatus.COMPLETED),
                isNull(), any());
        assertEquals(7_000L, ledger.balance(SOURCE_WALLET_ID));
    }

    /** 원장은 처리했지만 응답이 유실된 송금을 복구해도 잔액은 한 번만 바뀝니다. */
    @Test
    void recoveryCompletesTransferWhoseResponseWasLostWithoutMovingBalanceTwice() {
        Transfer lost = savedProcessingTransfer(KEY);
        ledger.register(LedgerTransactionRequest.of(lost));

        int settled = transferService.recoverDue(farFuture(), 10);

        assertEquals(1, settled);
        assertEquals(TransferStatus.COMPLETED, lost.getStatus());
        assertEquals(7_000L, ledger.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, ledger.balance(TARGET_WALLET_ID));
        assertEquals(List.of(LedgerTransactionRequest.of(lost), LedgerTransactionRequest.of(lost)), ledger.requests);
    }

    /** 복구 중 원장이 확정 거절하면 FAILED와 실패 사유를 저장하고, 예외 없이 다음 송금으로 넘어갑니다. */
    @Test
    void recoveryMarksTransferFailedWhenLedgerRejects() {
        ledger = new FakeLedger(1_000L, 0L);
        transferService = newServiceWith(ledger);
        Transfer transfer = savedProcessingTransfer(KEY);

        int settled = transferService.recoverDue(farFuture(), 10);

        assertEquals(1, settled);
        assertEquals(TransferStatus.FAILED, transfer.getStatus());
        assertEquals(TransferFailureReason.INSUFFICIENT_BALANCE, transfer.getFailureReason());
        assertEquals(1_000L, ledger.balance(SOURCE_WALLET_ID));
    }

    /** 결과를 계속 모르면 PROCESSING으로 두고 시도 횟수를 늘리며, 원장이 받아들이지 않은 송금은 MANUAL_REVIEW로 둡니다. */
    @Test
    void recoveryRecordsUnknownAttemptAndMovesNotAcceptedToManualReview() {
        Instant lastTriedAt = Instant.parse("2026-11-02T00:00:00Z");
        Transfer unknown = staleProcessingTransfer("unknown-key", lastTriedAt);
        Transfer notAccepted = staleProcessingTransfer("not-accepted-key", lastTriedAt.plusSeconds(1));
        ledger.onRegister = request -> {
            if (request.referenceId().equals(unknown.getId())) {
                throw TransferLedgerException.resultUnknown("원장 거래 등록 실패 status=503 code=-", null);
            }
            throw TransferLedgerException.requestNotAccepted(
                    "원장 거래 등록 실패 status=409 code=LEDGER_REFERENCE_CONFLICT", null);
        };

        int settled = transferService.recoverDue(farFuture(), 10);

        assertEquals(0, settled);
        assertEquals(TransferStatus.PROCESSING, unknown.getStatus());
        assertEquals(1, unknown.getAttemptCount());
        assertTrue(unknown.getUpdatedAt().isAfter(lastTriedAt));
        assertEquals(TransferStatus.MANUAL_REVIEW, notAccepted.getStatus());
        assertEquals(TransferReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, notAccepted.getReviewReason());
        verify(transferRepository, never()).finishProcessing(any(), any(), any(), any());
        assertEquals(10_000L, ledger.balance(SOURCE_WALLET_ID));
    }

    /**
     * 복구 중 원장이 400·409로 요청을 받아들이지 않으면 시도 횟수와 무관하게 바로 MANUAL_REVIEW로 두고,
     * 이후 복구 주기에서는 다시 보내지 않습니다(팀 복구 정책: 자동 복구가 어려운 경우).
     */
    @ParameterizedTest
    @CsvSource({
            "400, LEDGER_INVALID_REQUEST",
            "409, LEDGER_REFERENCE_CONFLICT"
    })
    void recoveryMovesRequestNotAcceptedToManualReviewAndStopsResending(int status, String code) {
        Instant lastTriedAt = Instant.parse("2026-11-02T00:00:00Z");
        Transfer transfer = staleProcessingTransfer(KEY, lastTriedAt);
        ReflectionTestUtils.setField(transfer, "attemptCount", 2);
        ledger.onRegister = request -> {
            throw TransferLedgerException.requestNotAccepted(
                    "원장 거래 등록 실패 status=" + status + " code=" + code, null);
        };

        transferService.recoverDue(farFuture(), 10);
        transferService.recoverDue(farFuture().plusSeconds(86_400), 10);

        assertEquals(TransferStatus.MANUAL_REVIEW, transfer.getStatus());
        assertEquals(TransferReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, transfer.getReviewReason());
        assertEquals(2, transfer.getAttemptCount());
        assertNull(transfer.getFailureReason());
        assertEquals(1, ledger.requests.size());
        assertEquals(10_000L, ledger.balance(SOURCE_WALLET_ID));
    }

    /** 재시도 대기 시간은 30초 × 2^(시도 횟수 - 1)입니다(30초 → 1분 → 2분 → 4분). 대기 시간이 지나기 전에는 다시 요청하지 않습니다. */
    @ParameterizedTest
    @CsvSource({"0, 30", "1, 30", "2, 60", "3, 120", "4, 240"})
    void recoveryWaitsBackoffBeforeRetrying(int attemptCount, long waitSeconds) {
        Instant lastTriedAt = Instant.parse("2026-11-02T00:00:00Z");
        Transfer transfer = staleProcessingTransfer(KEY, lastTriedAt);
        ReflectionTestUtils.setField(transfer, "attemptCount", attemptCount);

        transferService.recoverDue(lastTriedAt.plusSeconds(waitSeconds), 10);
        assertTrue(ledger.requests.isEmpty());

        transferService.recoverDue(lastTriedAt.plusSeconds(waitSeconds).plusMillis(1), 10);
        assertEquals(1, ledger.requests.size());
    }

    /** 처음 요청을 포함해 결과 모름이 5번이면 자동 처리를 멈추고 MANUAL_REVIEW(RETRY_EXHAUSTED)로 둡니다. 이후에는 다시 요청하지 않습니다. */
    @Test
    void movesToManualReviewAfterFiveUnknownResults() {
        ledger.onRegister = request -> {
            throw new IllegalStateException("connect timeout");
        };
        TransferResponse first = transferService.request(SENDER_ID, KEY, REQUEST);

        for (int i = 0; i < 4; i++) {
            transferService.recoverDue(farFuture(), 10);
        }
        Transfer transfer = onlyTransfer();

        assertEquals(TransferStatus.PROCESSING, first.status());
        assertEquals(5, ledger.requests.size());
        assertEquals(5, transfer.getAttemptCount());
        assertEquals(TransferStatus.MANUAL_REVIEW, transfer.getStatus());
        assertEquals(TransferReviewReason.RETRY_EXHAUSTED, transfer.getReviewReason());
        assertNull(transfer.getFailureReason());

        transferService.recoverDue(farFuture(), 10);
        TransferResponse retry = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(5, ledger.requests.size());
        assertEquals(TransferStatus.MANUAL_REVIEW, retry.status());
    }

    /** TC-21-U1: 시도 1~4회차는 PROCESSING·사유 없음을 유지하고, 정확히 5회차에 MANUAL_REVIEW로 바뀝니다. */
    @Test
    void staysProcessingUntilFourthUnknownResultAndMovesToManualReviewOnFifth() {
        ledger.onRegister = request -> {
            throw TransferLedgerException.resultUnknown("원장 거래 등록 실패 status=504 code=-", null);
        };
        transferService.request(SENDER_ID, KEY, REQUEST);

        for (int attempt = 1; attempt <= 4; attempt++) {
            Transfer transfer = onlyTransfer();
            assertEquals(attempt, transfer.getAttemptCount());
            assertEquals(TransferStatus.PROCESSING, transfer.getStatus());
            assertNull(transfer.getReviewReason());
            transferService.recoverDue(farFuture(), 10);
        }

        assertEquals(5, onlyTransfer().getAttemptCount());
        assertEquals(TransferStatus.MANUAL_REVIEW, onlyTransfer().getStatus());
        assertEquals(TransferReviewReason.RETRY_EXHAUSTED, onlyTransfer().getReviewReason());
        assertEquals(5, ledger.requests.size());
        // 모든 시도가 같은 reference·내용입니다.
        assertEquals(1, ledger.requests.stream().distinct().count());
    }

    /**
     * TC-21-U2: 결과 모름의 종류(일반 예외·RESULT_UNKNOWN·응답 없음·사유 없는 거절·원장 반영 후 기록 실패)가 섞여도 모두 한도에 셉니다.
     * 마지막 시도는 원장이 실제로 반영(POSTED)했지만 송금 쪽 기록이 실패한 경우로, 잔액은 이미 이동했는데 MANUAL_REVIEW가 됩니다.
     */
    @Test
    void countsEveryKindOfUnknownResultTowardRetryLimit() {
        ledger.onRegister = request -> {
            throw new IllegalStateException("read timeout");
        };
        transferService.request(SENDER_ID, KEY, REQUEST);
        ledger.onRegister = request -> {
            throw TransferLedgerException.resultUnknown("원장 거래 등록 실패 status=503 code=-", null);
        };
        transferService.recoverDue(farFuture(), 10);
        ledger.onRegister = request -> null;
        transferService.recoverDue(farFuture(), 10);
        ledger.onRegister = request -> LedgerTransactionResponse.rejected(UUID.randomUUID(), null);
        transferService.recoverDue(farFuture(), 10);
        assertEquals(4, onlyTransfer().getAttemptCount());
        assertEquals(TransferStatus.PROCESSING, onlyTransfer().getStatus());

        ledger.onRegister = ledger::apply;
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(transferRepository).finishProcessing(any(UUID.class), any(TransferStatus.class), any(), any());
        transferService.recoverDue(farFuture(), 10);

        Transfer transfer = onlyTransfer();
        assertEquals(5, transfer.getAttemptCount());
        assertEquals(TransferStatus.MANUAL_REVIEW, transfer.getStatus());
        assertEquals(TransferReviewReason.RETRY_EXHAUSTED, transfer.getReviewReason());
        assertNull(transfer.getFailureReason());
        assertEquals(7_000L, ledger.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, ledger.balance(TARGET_WALLET_ID));
    }

    /** TC-21-U3: 한도에 이르는 시도 중 다른 곳에서 먼저 확정되면(조건부 UPDATE 0행) MANUAL_REVIEW로 덮어쓰지 않습니다. */
    @Test
    void doesNotOverwriteTransferSettledElsewhereWhenReachingRetryLimit() {
        Transfer transfer = savedProcessingTransfer(KEY);
        ReflectionTestUtils.setField(transfer, "attemptCount", 4);
        ledger.onRegister = request -> {
            ReflectionTestUtils.setField(transfer, "status", TransferStatus.COMPLETED);
            throw TransferLedgerException.resultUnknown("원장 거래 등록 실패 status=-1 code=-", null);
        };

        transferService.recoverDue(farFuture(), 10);

        assertEquals(TransferStatus.COMPLETED, transfer.getStatus());
        assertEquals(4, transfer.getAttemptCount());
        assertNull(transfer.getReviewReason());
    }

    /**
     * TC-21-U4: 결과 모름 기록(시도 횟수 UPDATE)이 DB 오류로 실패하면 시도 횟수가 늘지 않고 PROCESSING(202)으로 응답하며,
     * 복구가 대기 시간 뒤 다시 시도해 그때부터 횟수를 셉니다.
     */
    @Test
    void keepsAttemptCountWhenUnknownResultCannotBeRecorded() {
        ledger.onRegister = request -> {
            throw TransferLedgerException.resultUnknown("원장 거래 등록 실패 status=-1 code=-", null);
        };
        doThrow(new DataAccessResourceFailureException("db down")).when(transferRepository)
                .recordUnknownAttempt(any(UUID.class), anyInt(), any(TransferStatus.class), any(), any(Instant.class));

        TransferResponse response = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.PROCESSING, response.status());
        assertEquals(0, onlyTransfer().getAttemptCount());

        reset(transferRepository);
        givenInMemoryRepository();
        transferService.recoverDue(farFuture(), 10);

        assertEquals(1, onlyTransfer().getAttemptCount());
        assertEquals(TransferStatus.PROCESSING, onlyTransfer().getStatus());
        assertEquals(2, ledger.requests.size());
    }

    /** 일괄 선점 뒤 다른 서버가 다시 선점한 송금(호출 직전 선점 확인 0행)은 원장에 보내지 않고, 시도 횟수도 그대로입니다. */
    @Test
    void recoverySkipsTransferReclaimedElsewhere() {
        Transfer transfer = savedProcessingTransfer(KEY);
        int attempts = transfer.getAttemptCount();
        when(transferRepository.renewRecoveryClaim(eq(transfer.getId()), any(Instant.class), any(Instant.class)))
                .thenReturn(0);

        int settled = transferService.recoverDue(farFuture(), 10);

        assertEquals(0, settled);
        assertTrue(ledger.requests.isEmpty());
        assertEquals(TransferStatus.PROCESSING, transfer.getStatus());
        assertEquals(attempts, transfer.getAttemptCount());
    }

    /** 한 건을 복구하다 예상하지 못한 예외가 나도 결과 모름으로 두고 다음 송금을 계속 복구합니다. */
    @Test
    void recoveryContinuesWithNextTransferWhenOneTransferFails() {
        Instant base = Instant.parse("2026-11-02T00:00:00Z");
        Transfer broken = staleProcessingTransfer("broken-key", base);
        Transfer next = staleProcessingTransfer("next-key", base.plusSeconds(10));
        ledger.onRegister = request -> {
            if (request.referenceId().equals(broken.getId())) {
                throw new IllegalStateException("unexpected");
            }
            return ledger.apply(request);
        };

        int settled = transferService.recoverDue(farFuture(), 10);

        assertEquals(1, settled);
        assertEquals(TransferStatus.PROCESSING, broken.getStatus());
        assertEquals(TransferStatus.COMPLETED, next.getStatus());
        assertEquals(List.of(broken.getId(), next.getId()),
                ledger.requests.stream().map(LedgerTransactionRequest::referenceId).toList());
    }

    /** 원장 결과에 상태가 없으면 해석할 수 없으므로 결과 모름으로 두고 기록하지 않습니다. */
    @Test
    void keepsProcessingWhenLedgerResultHasNoStatus() {
        ledger.onRegister = request -> new LedgerTransactionResponse(UUID.randomUUID(), null, null);

        TransferResponse response = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.PROCESSING, response.status());
        verify(transferRepository, never()).finishProcessing(any(), any(), any(), any());
    }

    /** 원장 결과를 연달아 3번 확인하지 못하면 원장 장애로 보고 남은 송금은 다음 주기로 넘깁니다. */
    @Test
    void recoveryStopsAfterThreeConsecutiveUnknownResults() {
        for (int i = 0; i < 5; i++) {
            savedProcessingTransfer("key-" + i);
        }
        ledger.onRegister = request -> {
            throw new IllegalStateException("connect timeout");
        };

        int settled = transferService.recoverDue(farFuture(), 10);

        assertEquals(0, settled);
        assertEquals(3, ledger.requests.size());
    }

    /** 원장이 받아들이지 않은 송금(409·400)은 원장 장애로 세지 않아, 여러 건이 앞에 몰려 있어도 뒤의 송금을 복구합니다. */
    @Test
    void recoveryDoesNotTreatRequestsNotAcceptedAsLedgerOutage() {
        Instant base = Instant.parse("2026-11-02T00:00:00Z");
        List<Transfer> notAccepted = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            notAccepted.add(staleProcessingTransfer("not-accepted-" + i, base.plusSeconds(i)));
        }
        List<UUID> notAcceptedIds = notAccepted.stream().map(Transfer::getId).toList();
        Transfer settled = staleProcessingTransfer("settled-key", base.plusSeconds(10));
        ledger.onRegister = request -> {
            if (notAcceptedIds.contains(request.referenceId())) {
                throw TransferLedgerException.requestNotAccepted(
                        "원장 거래 등록 실패 status=409 code=LEDGER_REFERENCE_CONFLICT", null);
            }
            return ledger.apply(request);
        };

        int recovered = transferService.recoverDue(farFuture(), 10);

        assertEquals(1, recovered);
        assertEquals(5, ledger.requests.size());
        assertEquals(TransferStatus.COMPLETED, settled.getStatus());
        notAccepted.forEach(transfer -> assertEquals(TransferStatus.MANUAL_REVIEW, transfer.getStatus()));
    }

    /** 결과를 모르는 송금 사이에 확정되는 송금이 있으면 연속 횟수를 다시 셉니다. */
    @Test
    void recoveryResetsConsecutiveUnknownCountWhenTransferIsSettled() {
        // 오래된 순으로 처리되므로 수정 시각을 정해 순서를 고정합니다. 세 번째 송금만 확정됩니다.
        Instant base = Instant.parse("2026-11-02T00:00:00Z");
        List<Transfer> transfers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            transfers.add(staleProcessingTransfer("key-" + i, base.plusSeconds(i)));
        }
        UUID settledId = transfers.get(2).getId();
        ledger = new FakeLedger(100_000L, 0L);
        transferService = newServiceWith(ledger);
        ledger.onRegister = request -> {
            if (request.referenceId().equals(settledId)) {
                return ledger.apply(request);
            }
            throw new IllegalStateException("connect timeout");
        };

        int recovered = transferService.recoverDue(farFuture(), 10);

        assertEquals(1, recovered);
        assertEquals(5, ledger.requests.size());
        assertEquals(TransferStatus.COMPLETED, transfers.get(2).getStatus());
    }

    /** 대기 시간이 지나지 않은(아직 첫 요청이 원장을 기다릴 수 있는) 송금과 처리 중이 아닌 송금은 다루지 않습니다. */
    @Test
    void recoverySkipsTransfersThatAreNotDueOrNotProcessing() {
        Transfer recent = savedProcessingTransfer(KEY);
        Transfer completed = savedProcessingTransfer("completed-key");
        ReflectionTestUtils.setField(completed, "status", TransferStatus.COMPLETED);

        int recovered = transferService.recoverDue(recent.getUpdatedAt().plusSeconds(30), 10);
        int recoveredLater = transferService.recoverDue(farFuture(), 10);

        assertEquals(0, recovered);
        assertEquals(1, recoveredLater);
        assertEquals(List.of(LedgerTransactionRequest.of(recent)), ledger.requests);
    }

    @Test
    void recoveryDoesNothingWithoutLedgerClient() {
        savedProcessingTransfer(KEY);
        TransferService withoutLedger = new TransferService(transferRepository, mock(TransferWalletReader.class),
                mock(TransferUserReader.class), Optional.empty());

        int recovered = withoutLedger.recoverDue(farFuture(), 10);

        assertEquals(0, recovered);
        verify(transferRepository, never()).claimRecoveryTargets(any(), any(), anyInt());
    }

    /** 모든 재시도 대기 시간(최대 4분)이 지난 시각입니다. */
    private static Instant farFuture() {
        return Instant.now().plusSeconds(3600);
    }

    private Transfer staleProcessingTransfer(String idempotencyKey, Instant updatedAt) {
        Transfer transfer = savedProcessingTransfer(idempotencyKey);
        ReflectionTestUtils.setField(transfer, "updatedAt", updatedAt);
        return transfer;
    }

    private TransferService newServiceWith(FakeLedger fakeLedger) {
        TransferWalletReader walletReader = mock(TransferWalletReader.class);
        when(walletReader.findActiveWalletId(SENDER_ID)).thenReturn(Optional.of(SOURCE_WALLET_ID));
        when(walletReader.findActiveWalletId(RECEIVER_ID)).thenReturn(Optional.of(TARGET_WALLET_ID));
        TransferUserReader userReader = mock(TransferUserReader.class);
        when(userReader.findRecipient(RECEIVER_ID))
                .thenReturn(Optional.of(new UserRecipientResponse(RECEIVER_ID, "홍길동")));
        return new TransferService(transferRepository, walletReader, userReader, Optional.of(fakeLedger));
    }

    private Transfer onlyTransfer() {
        assertEquals(1, store.size());
        return store.values().iterator().next();
    }

    private Transfer savedProcessingTransfer() {
        return savedProcessingTransfer(KEY);
    }

    private Transfer savedProcessingTransfer(String idempotencyKey) {
        Transfer transfer = Transfer.process(SENDER_ID, RECEIVER_ID, SOURCE_WALLET_ID, TARGET_WALLET_ID,
                REQUEST.amount(), REQUEST.currency(), REQUEST.memo(), idempotencyKey);
        ReflectionTestUtils.setField(transfer, "id", UUID.randomUUID());
        ReflectionTestUtils.invokeMethod(transfer, "onCreate");
        store.put(transfer.getId(), transfer);
        return transfer;
    }

    /** 송금 테이블의 UNIQUE(sender_id, idempotency_key)와 조건부 UPDATE를 메모리에서 흉내 냅니다. */
    private void givenInMemoryRepository() {
        when(transferRepository.findBySenderIdAndIdempotencyKey(any(UUID.class), any(String.class)))
                .thenAnswer(invocation -> findByKey(invocation.getArgument(0), invocation.getArgument(1)));
        when(transferRepository.saveAndFlush(any(Transfer.class))).thenAnswer(invocation -> {
            Transfer transfer = invocation.getArgument(0);
            if (findByKey(transfer.getSenderId(), transfer.getIdempotencyKey()).isPresent()) {
                throw new DataIntegrityViolationException("uk_transfer_sender_id_idempotency_key");
            }
            ReflectionTestUtils.setField(transfer, "id", UUID.randomUUID());
            ReflectionTestUtils.invokeMethod(transfer, "onCreate");
            store.put(transfer.getId(), transfer);
            return transfer;
        });
        when(transferRepository.findById(any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.<UUID>getArgument(0))));
        when(transferRepository.finishProcessing(any(UUID.class), any(TransferStatus.class), any(), any()))
                .thenAnswer(invocation -> {
                    Transfer transfer = processingOrNull(invocation.getArgument(0));
                    if (transfer == null) {
                        return 0;
                    }
                    ReflectionTestUtils.setField(transfer, "status", invocation.getArgument(1));
                    ReflectionTestUtils.setField(transfer, "failureReason", invocation.getArgument(2));
                    return 1;
                });
        // 대기 시간: 30초 × 2^max(attempt_count - 1, 0)
        // 선점: 대기 시간이 지난 송금을 오래된 순으로 고르고 수정 시각을 선점 시각으로 바꿉니다.
        when(transferRepository.claimRecoveryTargets(any(Instant.class), any(Instant.class), anyInt()))
                .thenAnswer(invocation -> {
                    Instant now = invocation.getArgument(0);
                    Instant claimedAt = invocation.getArgument(1);
                    int limit = invocation.getArgument(2);
                    List<Transfer> claimed = store.values().stream()
                            .filter(transfer -> transfer.getStatus() == TransferStatus.PROCESSING
                                    && transfer.getUpdatedAt().isBefore(now.minusSeconds(
                                            30L << Math.max(transfer.getAttemptCount() - 1, 0))))
                            .sorted(Comparator.comparing(Transfer::getUpdatedAt)
                                    .thenComparing(transfer -> transfer.getId().toString()))
                            .limit(limit)
                            .toList();
                    claimed.forEach(transfer -> ReflectionTestUtils.setField(transfer, "updatedAt", claimedAt));
                    return claimed;
                });
        when(transferRepository.renewRecoveryClaim(any(UUID.class), any(Instant.class), any(Instant.class)))
                .thenAnswer(invocation -> {
                    Transfer transfer = processingOrNull(invocation.getArgument(0));
                    if (transfer == null || !transfer.getUpdatedAt().equals(invocation.getArgument(1))) {
                        return 0;
                    }
                    ReflectionTestUtils.setField(transfer, "updatedAt", invocation.getArgument(2));
                    return 1;
                });
        when(transferRepository.recordUnknownAttempt(any(UUID.class), anyInt(), any(TransferStatus.class), any(),
                any(Instant.class)))
                .thenAnswer(invocation -> {
                    Transfer transfer = processingOrNull(invocation.getArgument(0));
                    int expected = invocation.getArgument(1);
                    if (transfer == null || transfer.getAttemptCount() != expected) {
                        return 0;
                    }
                    ReflectionTestUtils.setField(transfer, "attemptCount", expected + 1);
                    ReflectionTestUtils.setField(transfer, "status", invocation.getArgument(2));
                    ReflectionTestUtils.setField(transfer, "reviewReason", invocation.getArgument(3));
                    ReflectionTestUtils.setField(transfer, "updatedAt", invocation.getArgument(4));
                    return 1;
                });
        when(transferRepository.markManualReview(any(UUID.class), any(TransferReviewReason.class), any(Instant.class)))
                .thenAnswer(invocation -> {
                    Transfer transfer = processingOrNull(invocation.getArgument(0));
                    if (transfer == null) {
                        return 0;
                    }
                    ReflectionTestUtils.setField(transfer, "status", TransferStatus.MANUAL_REVIEW);
                    ReflectionTestUtils.setField(transfer, "reviewReason", invocation.getArgument(1));
                    ReflectionTestUtils.setField(transfer, "updatedAt", invocation.getArgument(2));
                    return 1;
                });
    }

    private Transfer processingOrNull(UUID transferId) {
        Transfer transfer = store.get(transferId);
        return transfer != null && transfer.getStatus() == TransferStatus.PROCESSING ? transfer : null;
    }

    private Optional<Transfer> findByKey(UUID senderId, String idempotencyKey) {
        return store.values().stream()
                .filter(transfer -> transfer.getSenderId().equals(senderId)
                        && transfer.getIdempotencyKey().equals(idempotencyKey))
                .findFirst();
    }

    /**
     * reference(송금 ID)별로 처음 결과를 기억해 같은 reference의 재요청에는 잔액을 바꾸지 않고 처음 결과(거절 포함)를 반환하는 테스트용 원장입니다.
     * 차감 항목의 잔액이 모자라면 어떤 항목도 반영하지 않습니다. rejectWith로 계정 거절을, onRegister로 타임아웃·결과 모름을 흉내 냅니다.
     */
    private static final class FakeLedger implements TransferLedgerClient {

        private final Map<UUID, Long> balances = new HashMap<>();
        private final Map<UUID, LedgerTransactionResponse> processed = new HashMap<>();
        private final List<LedgerTransactionRequest> requests = new ArrayList<>();
        private Handler onRegister = this::apply;
        private RejectReason rejectWith;

        private FakeLedger(long sourceBalance, long targetBalance) {
            balances.put(SOURCE_WALLET_ID, sourceBalance);
            balances.put(TARGET_WALLET_ID, targetBalance);
        }

        @Override
        public LedgerTransactionResponse register(LedgerTransactionRequest request) {
            requests.add(request);
            return onRegister.handle(request);
        }

        private LedgerTransactionResponse apply(LedgerTransactionRequest request) {
            return processed.computeIfAbsent(request.referenceId(), referenceId -> post(request));
        }

        private LedgerTransactionResponse post(LedgerTransactionRequest request) {
            UUID transactionId = UUID.randomUUID();
            if (rejectWith != null) {
                return LedgerTransactionResponse.rejected(transactionId, rejectWith);
            }
            for (LedgerTransactionRequest.Entry entry : request.entries()) {
                if (entry.direction().equals("DEBIT") && balance(entry.walletId()) < entry.amount()) {
                    return LedgerTransactionResponse.rejected(transactionId, RejectReason.INSUFFICIENT_BALANCE);
                }
            }
            for (LedgerTransactionRequest.Entry entry : request.entries()) {
                long delta = entry.direction().equals("DEBIT") ? -entry.amount() : entry.amount();
                balances.put(entry.walletId(), balance(entry.walletId()) + delta);
            }
            return LedgerTransactionResponse.posted(transactionId);
        }

        private long balance(UUID walletId) {
            return balances.getOrDefault(walletId, 0L);
        }

        @FunctionalInterface
        private interface Handler {
            LedgerTransactionResponse handle(LedgerTransactionRequest request);
        }
    }
}
