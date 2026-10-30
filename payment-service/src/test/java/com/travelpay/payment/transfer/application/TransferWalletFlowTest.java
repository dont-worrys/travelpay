package com.travelpay.payment.transfer.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.payment.transfer.client.TransferWalletReader;
import com.travelpay.payment.transfer.client.WalletTransferClient;
import com.travelpay.payment.transfer.client.WalletTransferRequest;
import com.travelpay.payment.transfer.client.WalletTransferResult;
import com.travelpay.payment.transfer.domain.Transfer;
import com.travelpay.payment.transfer.domain.TransferErrorCode;
import com.travelpay.payment.transfer.domain.TransferRepository;
import com.travelpay.payment.transfer.domain.TransferStatus;
import com.travelpay.payment.transfer.dto.TransferCreateRequest;
import com.travelpay.payment.transfer.dto.TransferResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 지갑 송금 처리 구현이 있을 때의 송금 흐름을 검증합니다.
 * 실제 Wallet·원장 대신 잔액과 처리한 송금 ID를 기억하는 테스트용 지갑을 사용하며, 실제 잔액은 변경하지 않습니다.
 * 테스트용 지갑의 중복 처리 방지는 실제 구현체가 보장해야 할 계약을 흉내 낸 것으로,
 * 이 테스트는 송금 서비스가 재전송 때 같은 송금 ID·내용을 보내는지를 확인합니다.
 */
class TransferWalletFlowTest {

    private static final UUID SENDER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID RECEIVER_ID = UUID.fromString("4c6a0178-266b-4f1e-b811-a2767026be70");
    private static final UUID SOURCE_WALLET_ID = UUID.fromString("0f8e6d4c-2b1a-4f9e-8d7c-6b5a4f3e2d1c");
    private static final UUID TARGET_WALLET_ID = UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    private static final String KEY = "c2f1d0e9-transfer-key";
    private static final TransferCreateRequest REQUEST = new TransferCreateRequest(RECEIVER_ID, 3_000L, "KRW", "저녁값");

    private final Map<UUID, Transfer> store = new LinkedHashMap<>();
    private TransferRepository transferRepository;
    private FakeWallet wallet;
    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferRepository = mock(TransferRepository.class);
        givenInMemoryRepository();
        TransferWalletReader walletReader = mock(TransferWalletReader.class);
        when(walletReader.findActiveWalletId(SENDER_ID)).thenReturn(Optional.of(SOURCE_WALLET_ID));
        when(walletReader.findActiveWalletId(RECEIVER_ID)).thenReturn(Optional.of(TARGET_WALLET_ID));
        wallet = new FakeWallet(10_000L, 0L);
        transferService = new TransferService(transferRepository, walletReader, Optional.of(wallet));
    }

    @Test
    void completesTransferByDebitingSenderAndCreditingReceiver() {
        TransferResponse response = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.COMPLETED, response.status());
        assertEquals(7_000L, wallet.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, wallet.balance(TARGET_WALLET_ID));
        assertEquals(List.of(new WalletTransferRequest(response.transferId(), SOURCE_WALLET_ID, TARGET_WALLET_ID,
                "KRW", 3_000L)), wallet.requests);
    }

    @Test
    void savesProcessingBeforeCallingWallet() {
        wallet.onTransfer = request -> {
            assertEquals(TransferStatus.PROCESSING, store.get(request.transferId()).getStatus());
            return wallet.apply(request);
        };

        transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(1, wallet.requests.size());
    }

    @Test
    void failsWithoutChangingBalancesWhenSenderBalanceIsInsufficient() {
        wallet = new FakeWallet(1_000L, 0L);
        transferService = newServiceWith(wallet);

        var exception = assertThrows(BusinessException.class, () -> transferService.request(SENDER_ID, KEY, REQUEST));

        assertSame(TransferErrorCode.INSUFFICIENT_BALANCE, exception.getErrorCode());
        assertEquals(TransferStatus.FAILED, onlyTransfer().getStatus());
        assertEquals(1_000L, wallet.balance(SOURCE_WALLET_ID));
        assertEquals(0L, wallet.balance(TARGET_WALLET_ID));
    }

    @Test
    void returnsCompletedTransferForRetryWithoutCallingWalletAgain() {
        TransferResponse first = transferService.request(SENDER_ID, KEY, REQUEST);

        TransferResponse retry = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(first.transferId(), retry.transferId());
        assertEquals(TransferStatus.COMPLETED, retry.status());
        assertEquals(1, wallet.requests.size());
        assertEquals(7_000L, wallet.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, wallet.balance(TARGET_WALLET_ID));
    }

    @Test
    void returnsSameInsufficientBalanceErrorForRetryWithoutCallingWalletAgain() {
        wallet = new FakeWallet(1_000L, 0L);
        transferService = newServiceWith(wallet);
        assertThrows(BusinessException.class, () -> transferService.request(SENDER_ID, KEY, REQUEST));

        var retry = assertThrows(BusinessException.class, () -> transferService.request(SENDER_ID, KEY, REQUEST));

        assertSame(TransferErrorCode.INSUFFICIENT_BALANCE, retry.getErrorCode());
        assertEquals(1, wallet.requests.size());
    }

    @Test
    void keepsProcessingWhenWalletTimesOut() {
        wallet.onTransfer = request -> {
            throw new IllegalStateException("read timeout");
        };

        TransferResponse response = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.PROCESSING, response.status());
        assertEquals(TransferStatus.PROCESSING, onlyTransfer().getStatus());
        verify(transferRepository, never()).finishProcessing(any(), any(), any());
        assertEquals(10_000L, wallet.balance(SOURCE_WALLET_ID));
    }

    @Test
    void keepsProcessingWhenWalletResultIsUnknown() {
        wallet.onTransfer = request -> WalletTransferResult.UNKNOWN;

        TransferResponse response = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.PROCESSING, response.status());
        verify(transferRepository, never()).finishProcessing(any(), any(), any());
    }

    /** 처음 요청은 지갑에서 처리됐지만 응답만 유실된 경우입니다. */
    @Test
    void resendsSameRequestForProcessingTransferWithoutMovingBalanceTwice() {
        wallet.onTransfer = request -> {
            wallet.apply(request);
            throw new IllegalStateException("read timeout");
        };
        TransferResponse first = transferService.request(SENDER_ID, KEY, REQUEST);
        assertEquals(TransferStatus.PROCESSING, first.status());
        wallet.onTransfer = wallet::apply;

        TransferResponse retry = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(first.transferId(), retry.transferId());
        assertEquals(TransferStatus.COMPLETED, retry.status());
        assertEquals(7_000L, wallet.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, wallet.balance(TARGET_WALLET_ID));
        assertEquals(2, wallet.requests.size());
        assertEquals(wallet.requests.get(0), wallet.requests.get(1));
    }

    /** 지갑 처리는 성공했지만 결과 반영(DB) 중 오류가 난 경우입니다. 500 대신 처리 중으로 응답하고 재요청으로 반영합니다. */
    @Test
    void keepsProcessingWhenResultCannotBeSavedAndCompletesOnRetryWithoutMovingBalanceTwice() {
        when(transferRepository.finishProcessing(any(UUID.class), any(TransferStatus.class), any()))
                .thenThrow(new DataIntegrityViolationException("ck_transfer_status"))
                .thenAnswer(invocation -> {
                    ReflectionTestUtils.setField(store.get(invocation.<UUID>getArgument(0)), "status",
                            invocation.getArgument(1));
                    return 1;
                });

        TransferResponse first = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.PROCESSING, first.status());
        assertEquals(TransferStatus.PROCESSING, onlyTransfer().getStatus());
        assertEquals(7_000L, wallet.balance(SOURCE_WALLET_ID));

        TransferResponse retry = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.COMPLETED, retry.status());
        assertEquals(7_000L, wallet.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, wallet.balance(TARGET_WALLET_ID));
        assertEquals(2, wallet.requests.size());
    }

    /** 처음 요청이 지갑에 도달하지 못한 경우입니다. */
    @Test
    void resendsProcessingTransferAndMovesBalanceOnceWhenFirstRequestWasNotProcessed() {
        wallet.onTransfer = request -> {
            throw new IllegalStateException("connect timeout");
        };
        transferService.request(SENDER_ID, KEY, REQUEST);
        wallet.onTransfer = wallet::apply;

        TransferResponse retry = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.COMPLETED, retry.status());
        assertEquals(7_000L, wallet.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, wallet.balance(TARGET_WALLET_ID));
    }

    @Test
    void rejectsRetryWithDifferentRequestWithoutCallingWallet() {
        wallet.onTransfer = request -> WalletTransferResult.UNKNOWN;
        transferService.request(SENDER_ID, KEY, REQUEST);

        var exception = assertThrows(BusinessException.class, () -> transferService.request(SENDER_ID, KEY,
                new TransferCreateRequest(RECEIVER_ID, 5_000L, "KRW", "저녁값")));

        assertSame(TransferErrorCode.IDEMPOTENCY_KEY_REUSED, exception.getErrorCode());
        assertEquals(1, wallet.requests.size());
    }

    /** 같은 키의 첫 요청이 저장된 뒤 지갑 처리 중일 때 들어온 요청은 UNIQUE 위반 후 저장된 송금으로 재전송합니다. */
    @Test
    void concurrentDuplicateResendsSameTransferWithoutMovingBalanceTwice() {
        Transfer inFlight = savedProcessingTransfer();
        // 첫 요청의 지갑 처리입니다. 지갑에는 반영됐지만 첫 요청은 아직 결과를 반영하지 않았습니다.
        wallet.transfer(WalletTransferRequest.of(inFlight));
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY))
                .thenReturn(Optional.empty())
                .thenAnswer(invocation -> Optional.of(inFlight));

        TransferResponse response = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(inFlight.getId(), response.transferId());
        assertEquals(TransferStatus.COMPLETED, response.status());
        assertEquals(1, store.size());
        assertEquals(7_000L, wallet.balance(SOURCE_WALLET_ID));
        assertEquals(3_000L, wallet.balance(TARGET_WALLET_ID));
        assertEquals(List.of(WalletTransferRequest.of(inFlight), WalletTransferRequest.of(inFlight)), wallet.requests);
    }

    /** 동시 재요청이 먼저 결과를 반영해 조건부 UPDATE가 0행이면, 다시 읽은 상태를 응답합니다. */
    @Test
    void returnsStateFinishedByConcurrentRequestWhenConditionalUpdateMisses() {
        wallet.onTransfer = request -> {
            WalletTransferResult result = wallet.apply(request);
            ReflectionTestUtils.setField(store.get(request.transferId()), "status", TransferStatus.COMPLETED);
            return result;
        };

        TransferResponse response = transferService.request(SENDER_ID, KEY, REQUEST);

        assertEquals(TransferStatus.COMPLETED, response.status());
        verify(transferRepository).finishProcessing(eq(response.transferId()), eq(TransferStatus.COMPLETED), any());
        assertEquals(7_000L, wallet.balance(SOURCE_WALLET_ID));
    }

    private TransferService newServiceWith(FakeWallet fakeWallet) {
        TransferWalletReader walletReader = mock(TransferWalletReader.class);
        when(walletReader.findActiveWalletId(SENDER_ID)).thenReturn(Optional.of(SOURCE_WALLET_ID));
        when(walletReader.findActiveWalletId(RECEIVER_ID)).thenReturn(Optional.of(TARGET_WALLET_ID));
        return new TransferService(transferRepository, walletReader, Optional.of(fakeWallet));
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
        when(transferRepository.finishProcessing(any(UUID.class), any(TransferStatus.class), any()))
                .thenAnswer(invocation -> {
                    Transfer transfer = store.get(invocation.<UUID>getArgument(0));
                    if (transfer == null || transfer.getStatus() != TransferStatus.PROCESSING) {
                        return 0;
                    }
                    ReflectionTestUtils.setField(transfer, "status", invocation.getArgument(1));
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
     * 송금 ID별로 처음 결과를 기억해 같은 송금 ID의 재요청에는 잔액을 바꾸지 않고 처음 결과를 반환하는 테스트용 지갑입니다.
     * onTransfer를 바꿔 타임아웃·결과 모름을 흉내 냅니다.
     */
    private static final class FakeWallet implements WalletTransferClient {

        private final Map<UUID, Long> balances = new HashMap<>();
        private final Map<UUID, WalletTransferResult> processed = new HashMap<>();
        private final List<WalletTransferRequest> requests = new ArrayList<>();
        private Handler onTransfer = this::apply;

        private FakeWallet(long sourceBalance, long targetBalance) {
            balances.put(SOURCE_WALLET_ID, sourceBalance);
            balances.put(TARGET_WALLET_ID, targetBalance);
        }

        @Override
        public WalletTransferResult transfer(WalletTransferRequest request) {
            requests.add(request);
            return onTransfer.handle(request);
        }

        private WalletTransferResult apply(WalletTransferRequest request) {
            WalletTransferResult previous = processed.get(request.transferId());
            if (previous != null) {
                return previous;
            }
            long sourceBalance = balance(request.sourceWalletId());
            WalletTransferResult result;
            if (sourceBalance < request.amount()) {
                result = WalletTransferResult.INSUFFICIENT_BALANCE;
            } else {
                balances.put(request.sourceWalletId(), sourceBalance - request.amount());
                balances.put(request.targetWalletId(), balance(request.targetWalletId()) + request.amount());
                result = WalletTransferResult.SUCCEEDED;
            }
            processed.put(request.transferId(), result);
            return result;
        }

        private long balance(UUID walletId) {
            return balances.getOrDefault(walletId, 0L);
        }

        @FunctionalInterface
        private interface Handler {
            WalletTransferResult handle(WalletTransferRequest request);
        }
    }
}
