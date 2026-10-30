package com.travelpay.payment.transfer.application;

import java.util.ArrayList;
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
import com.travelpay.payment.transfer.client.TransferWalletReader;
import com.travelpay.payment.transfer.domain.Transfer;
import com.travelpay.payment.transfer.domain.TransferErrorCode;
import com.travelpay.payment.transfer.domain.TransferFailureReason;
import com.travelpay.payment.transfer.domain.TransferRepository;
import com.travelpay.payment.transfer.domain.TransferStatus;
import com.travelpay.payment.transfer.dto.TransferCreateRequest;
import com.travelpay.payment.transfer.dto.TransferResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    /** 응답이 없거나 거절 사유가 빠지는 등 결과를 해석할 수 없으면 실패로 단정하지 않습니다. */
    @Test
    void keepsProcessingWhenLedgerResultIsUnknown() {
        ledger.onRegister = request -> null;
        TransferResponse noResult = transferService.request(SENDER_ID, KEY, REQUEST);
        ledger.onRegister = request -> LedgerTransactionResponse.rejected(UUID.randomUUID(), null);
        TransferResponse noReason = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.PROCESSING, noResult.status());
        assertEquals(TransferStatus.PROCESSING, noReason.status());
        verify(transferRepository, never()).finishProcessing(any(), any(), any(), any());
    }

    /** 처음 요청은 원장에서 처리됐지만 응답만 유실된 경우입니다. */
    @Test
    void resendsSameReferenceForProcessingTransferWithoutMovingBalanceTwice() {
        ledger.onRegister = request -> {
            ledger.apply(request);
            throw new IllegalStateException("read timeout");
        };
        TransferResponse first = transferService.request(SENDER_ID, KEY, REQUEST);
        assertEquals(TransferStatus.PROCESSING, first.status());
        ledger.onRegister = ledger::apply;

        TransferResponse retry = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(first.transferId(), retry.transferId());
        assertEquals(TransferStatus.COMPLETED, retry.status());
        assertEquals(7_000L, ledger.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, ledger.balance(TARGET_WALLET_ID));
        assertEquals(2, ledger.requests.size());
        assertEquals(first.transferId(), ledger.requests.get(1).referenceId());
        assertEquals(ledger.requests.get(0), ledger.requests.get(1));
    }

    /** 원장 처리는 성공했지만 결과 반영(DB) 중 오류가 난 경우입니다. 500 대신 처리 중으로 응답하고 재요청으로 반영합니다. */
    @Test
    void keepsProcessingWhenResultCannotBeSavedAndCompletesOnRetryWithoutMovingBalanceTwice() {
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

        TransferResponse retry = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.COMPLETED, retry.status());
        assertEquals(7_000L, ledger.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, ledger.balance(TARGET_WALLET_ID));
        assertEquals(2, ledger.requests.size());
    }

    /** 처음 요청이 원장에 도달하지 못한 경우입니다. */
    @Test
    void resendsProcessingTransferAndMovesBalanceOnceWhenFirstRequestWasNotProcessed() {
        ledger.onRegister = request -> {
            throw new IllegalStateException("connect timeout");
        };
        transferService.request(SENDER_ID, KEY, REQUEST);
        ledger.onRegister = ledger::apply;

        TransferResponse retry = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.COMPLETED, retry.status());
        assertEquals(7_000L, ledger.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, ledger.balance(TARGET_WALLET_ID));
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

    /** 같은 키의 첫 요청이 저장된 뒤 원장 처리 중일 때 들어온 요청은 UNIQUE 위반 후 저장된 송금으로 재전송합니다. */
    @Test
    void concurrentDuplicateResendsSameTransferWithoutMovingBalanceTwice() {
        Transfer inFlight = savedProcessingTransfer();
        // 첫 요청의 원장 처리입니다. 원장에는 반영됐지만 첫 요청은 아직 결과를 반영하지 않았습니다.
        ledger.register(LedgerTransactionRequest.of(inFlight));
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY))
                .thenReturn(Optional.empty())
                .thenAnswer(invocation -> Optional.of(inFlight));

        TransferResponse response = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(inFlight.getId(), response.transferId());
        assertEquals(TransferStatus.COMPLETED, response.status());
        assertEquals(1, store.size());
        assertEquals(7_000L, ledger.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, ledger.balance(TARGET_WALLET_ID));
        assertEquals(List.of(LedgerTransactionRequest.of(inFlight), LedgerTransactionRequest.of(inFlight)),
                ledger.requests);
    }

    /** 동시 재요청이 먼저 결과를 반영해 조건부 UPDATE가 0행이면, 다시 읽은 상태를 응답합니다. */
    @Test
    void returnsStateFinishedByConcurrentRequestWhenConditionalUpdateMisses() {
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

    private TransferService newServiceWith(FakeLedger fakeLedger) {
        TransferWalletReader walletReader = mock(TransferWalletReader.class);
        when(walletReader.findActiveWalletId(SENDER_ID)).thenReturn(Optional.of(SOURCE_WALLET_ID));
        when(walletReader.findActiveWalletId(RECEIVER_ID)).thenReturn(Optional.of(TARGET_WALLET_ID));
        return new TransferService(transferRepository, walletReader, Optional.of(fakeLedger));
    }

    private Transfer onlyTransfer() {
        assertEquals(1, store.size());
        return store.values().iterator().next();
    }

    private Transfer savedProcessingTransfer() {
        Transfer transfer = Transfer.process(SENDER_ID, RECEIVER_ID, SOURCE_WALLET_ID, TARGET_WALLET_ID,
                REQUEST.amount(), REQUEST.currency(), REQUEST.memo(), KEY,
                TransferRequestHasher.hash(RECEIVER_ID, REQUEST.amount(), REQUEST.currency(), REQUEST.memo()));
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
                    Transfer transfer = store.get(invocation.<UUID>getArgument(0));
                    if (transfer == null || transfer.getStatus() != TransferStatus.PROCESSING) {
                        return 0;
                    }
                    ReflectionTestUtils.setField(transfer, "status", invocation.getArgument(1));
                    ReflectionTestUtils.setField(transfer, "failureReason", invocation.getArgument(2));
                    return 1;
                });
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
