package com.travelpay.payment.transfer.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.payment.transfer.client.TransferWalletReader;
import com.travelpay.payment.transfer.client.WalletResponse;
import com.travelpay.payment.transfer.domain.Transfer;
import com.travelpay.payment.transfer.domain.TransferDirection;
import com.travelpay.payment.transfer.domain.TransferErrorCode;
import com.travelpay.payment.transfer.domain.TransferRepository;
import com.travelpay.payment.transfer.domain.TransferStatus;
import com.travelpay.payment.transfer.dto.TransferCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransferServiceTest {

    private static final UUID SENDER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID RECEIVER_ID = UUID.fromString("4c6a0178-266b-4f1e-b811-a2767026be70");
    private static final UUID OTHER_USER_ID = UUID.fromString("9b2f4a51-7d0e-4c3a-8f61-2e5b8c9d0a14");
    private static final UUID SOURCE_WALLET_ID = UUID.fromString("0f8e6d4c-2b1a-4f9e-8d7c-6b5a4f3e2d1c");
    private static final UUID TARGET_WALLET_ID = UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    private static final String KEY = "c2f1d0e9-transfer-key";

    private TransferRepository transferRepository;
    private TransferWalletReader walletReader;
    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferRepository = mock(TransferRepository.class);
        walletReader = mock(TransferWalletReader.class);
        transferService = new TransferService(transferRepository, walletReader);
    }

    @Test
    void acceptsValidTransferAsRequestedWithBothActiveWallets() {
        givenActiveWallets();
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.empty());
        when(transferRepository.saveAndFlush(any(Transfer.class))).thenAnswer(invocation -> persisted(invocation.getArgument(0)));

        var response = transferService.request(SENDER_ID, KEY, request(10_000L, "KRW", "  저녁값  "));

        assertEquals(TransferStatus.REQUESTED, response.status());
        assertEquals(TransferDirection.SENT, response.direction());
        assertEquals(10_000L, response.amount());
        assertEquals("저녁값", response.memo());
        verify(transferRepository).saveAndFlush(any(Transfer.class));
    }

    @Test
    void storesBothWalletIdsAndNormalizedRequestHash() {
        givenActiveWallets();
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.empty());
        List<Transfer> saved = new ArrayList<>();
        when(transferRepository.saveAndFlush(any(Transfer.class))).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return persisted(invocation.getArgument(0));
        });

        transferService.request(SENDER_ID, KEY, request(10_000L, "KRW", "   "));

        Transfer transfer = saved.getFirst();
        assertEquals(SOURCE_WALLET_ID, transfer.getSourceWalletId());
        assertEquals(TARGET_WALLET_ID, transfer.getTargetWalletId());
        assertNull(transfer.getMemo());
        assertEquals(TransferRequestHasher.hash(RECEIVER_ID, 10_000L, "KRW", null), transfer.getRequestHash());
    }

    @Test
    void returnsExistingTransferForSameKeyAndSameRequestWithoutCallingWallet() {
        Transfer existing = persisted(transfer(10_000L, "KRW", null));
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.of(existing));

        var response = transferService.request(SENDER_ID, KEY, request(10_000L, "KRW", null));

        assertEquals(existing.getId(), response.transferId());
        verifyNoInteractions(walletReader);
        verify(transferRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsSameKeyWithDifferentRequest() {
        Transfer existing = persisted(transfer(10_000L, "KRW", null));
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.of(existing));

        var exception = assertThrows(BusinessException.class,
                () -> transferService.request(SENDER_ID, KEY, request(20_000L, "KRW", null)));

        assertSame(TransferErrorCode.IDEMPOTENCY_KEY_REUSED, exception.getErrorCode());
        verify(transferRepository, never()).saveAndFlush(any());
    }

    @Test
    void returnsTransferSavedByConcurrentRequestWhenUniqueKeyConflicts() {
        givenActiveWallets();
        Transfer concurrent = persisted(transfer(10_000L, "KRW", null));
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrent));
        when(transferRepository.saveAndFlush(any(Transfer.class)))
                .thenThrow(new DataIntegrityViolationException("uk_transfer_sender_id_idempotency_key"));

        var response = transferService.request(SENDER_ID, KEY, request(10_000L, "KRW", null));

        assertEquals(concurrent.getId(), response.transferId());
    }

    @Test
    void rethrowsIntegrityViolationUnrelatedToIdempotencyKey() {
        givenActiveWallets();
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.empty());
        var violation = new DataIntegrityViolationException("ck_transfer_amount");
        when(transferRepository.saveAndFlush(any(Transfer.class))).thenThrow(violation);

        var exception = assertThrows(DataIntegrityViolationException.class,
                () -> transferService.request(SENDER_ID, KEY, request(10_000L, "KRW", null)));

        assertSame(violation, exception);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "key with space", "키"})
    void rejectsInvalidIdempotencyKey(String key) {
        var exception = assertThrows(BusinessException.class,
                () -> transferService.request(SENDER_ID, key, request(10_000L, "KRW", null)));

        assertSame(TransferErrorCode.INVALID_IDEMPOTENCY_KEY, exception.getErrorCode());
        verifyNoInteractions(transferRepository, walletReader);
    }

    @Test
    void rejectsMissingOrTooLongIdempotencyKey() {
        var missing = assertThrows(BusinessException.class,
                () -> transferService.request(SENDER_ID, null, request(10_000L, "KRW", null)));
        var tooLong = assertThrows(BusinessException.class,
                () -> transferService.request(SENDER_ID, "a".repeat(101), request(10_000L, "KRW", null)));

        assertSame(TransferErrorCode.INVALID_IDEMPOTENCY_KEY, missing.getErrorCode());
        assertSame(TransferErrorCode.INVALID_IDEMPOTENCY_KEY, tooLong.getErrorCode());
    }

    @Test
    void rejectsTransferToSelf() {
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.empty());
        var selfRequest = new TransferCreateRequest(SENDER_ID, 10_000L, "KRW", null);

        var exception = assertThrows(BusinessException.class,
                () -> transferService.request(SENDER_ID, KEY, selfRequest));

        assertSame(TransferErrorCode.SELF_TRANSFER, exception.getErrorCode());
        verifyNoInteractions(walletReader);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "EUR"})
    void rejectsUnsupportedCurrency(String currency) {
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.empty());

        var exception = assertThrows(BusinessException.class,
                () -> transferService.request(SENDER_ID, KEY, request(10_000L, currency, null)));

        assertSame(TransferErrorCode.UNSUPPORTED_CURRENCY, exception.getErrorCode());
        verifyNoInteractions(walletReader);
    }

    @Test
    void rejectsWhenSenderHasNoActiveWallet() {
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.empty());
        when(walletReader.findActiveWalletId(SENDER_ID)).thenReturn(Optional.empty());

        var exception = assertThrows(BusinessException.class,
                () -> transferService.request(SENDER_ID, KEY, request(10_000L, "KRW", null)));

        assertSame(TransferErrorCode.SENDER_WALLET_UNAVAILABLE, exception.getErrorCode());
        verify(transferRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsWhenReceiverHasNoActiveWallet() {
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.empty());
        when(walletReader.findActiveWalletId(SENDER_ID)).thenReturn(Optional.of(SOURCE_WALLET_ID));
        when(walletReader.findActiveWalletId(RECEIVER_ID)).thenReturn(Optional.empty());

        var exception = assertThrows(BusinessException.class,
                () -> transferService.request(SENDER_ID, KEY, request(10_000L, "KRW", null)));

        assertSame(TransferErrorCode.RECEIVER_UNAVAILABLE, exception.getErrorCode());
        verify(transferRepository, never()).saveAndFlush(any());
    }

    @Test
    void showsRecipientWithActiveWalletAsTransferable() {
        when(walletReader.findWallet(RECEIVER_ID)).thenReturn(Optional.of(new WalletResponse(TARGET_WALLET_ID, "ACTIVE")));

        var response = transferService.findRecipient(SENDER_ID, RECEIVER_ID);

        assertEquals(RECEIVER_ID, response.receiverId());
        assertTrue(response.transferable());
        verifyNoInteractions(transferRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SUSPENDED", "CLOSED"})
    void showsRecipientWithNonActiveWalletAsNotTransferable(String status) {
        when(walletReader.findWallet(RECEIVER_ID)).thenReturn(Optional.of(new WalletResponse(TARGET_WALLET_ID, status)));

        assertFalse(transferService.findRecipient(SENDER_ID, RECEIVER_ID).transferable());
    }

    @Test
    void returnsNotFoundForRecipientWithoutWallet() {
        when(walletReader.findWallet(RECEIVER_ID)).thenReturn(Optional.empty());

        var exception = assertThrows(BusinessException.class,
                () -> transferService.findRecipient(SENDER_ID, RECEIVER_ID));

        assertSame(TransferErrorCode.RECEIVER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void rejectsSelfAsRecipientWithoutCallingWallet() {
        var exception = assertThrows(BusinessException.class,
                () -> transferService.findRecipient(SENDER_ID, SENDER_ID));

        assertSame(TransferErrorCode.SELF_TRANSFER, exception.getErrorCode());
        verifyNoInteractions(walletReader);
    }

    @Test
    void propagatesWalletServiceErrorOnRecipientLookup() {
        when(walletReader.findWallet(RECEIVER_ID))
                .thenThrow(new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE));

        var exception = assertThrows(BusinessException.class,
                () -> transferService.findRecipient(SENDER_ID, RECEIVER_ID));

        assertSame(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void showsTransferToSenderAndReceiverWithTheirDirection() {
        Transfer existing = persisted(transfer(10_000L, "KRW", null));
        when(transferRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertEquals(TransferDirection.SENT, transferService.get(SENDER_ID, existing.getId()).direction());
        assertEquals(TransferDirection.RECEIVED, transferService.get(RECEIVER_ID, existing.getId()).direction());
    }

    @Test
    void hidesTransferFromNonParticipantAsNotFound() {
        Transfer existing = persisted(transfer(10_000L, "KRW", null));
        when(transferRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        UUID missingId = UUID.randomUUID();
        when(transferRepository.findById(missingId)).thenReturn(Optional.empty());

        var otherUser = assertThrows(BusinessException.class,
                () -> transferService.get(OTHER_USER_ID, existing.getId()));
        var missing = assertThrows(BusinessException.class,
                () -> transferService.get(SENDER_ID, missingId));

        assertSame(TransferErrorCode.NOT_FOUND, otherUser.getErrorCode());
        assertSame(TransferErrorCode.NOT_FOUND, missing.getErrorCode());
    }

    @Test
    void cancelsRequestedTransferBySender() {
        Transfer existing = persisted(transfer(10_000L, "KRW", null));
        when(transferRepository.cancelRequested(eq(existing.getId()), eq(SENDER_ID), any(Instant.class)))
                .thenAnswer(invocation -> {
                    // 조건부 UPDATE로 DB 상태가 바뀐 결과를 흉내 냅니다.
                    ReflectionTestUtils.setField(existing, "status", TransferStatus.CANCELLED);
                    return 1;
                });
        when(transferRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        var response = transferService.cancel(SENDER_ID, existing.getId());

        assertEquals(existing.getId(), response.transferId());
        assertEquals(TransferStatus.CANCELLED, response.status());
        assertEquals(TransferDirection.SENT, response.direction());
    }

    @Test
    void returnsCurrentStateWhenTransferIsAlreadyCancelled() {
        Transfer existing = persisted(transfer(10_000L, "KRW", null));
        ReflectionTestUtils.setField(existing, "status", TransferStatus.CANCELLED);
        when(transferRepository.cancelRequested(eq(existing.getId()), eq(SENDER_ID), any(Instant.class)))
                .thenReturn(0);
        when(transferRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        var response = transferService.cancel(SENDER_ID, existing.getId());

        assertEquals(TransferStatus.CANCELLED, response.status());
    }

    @Test
    void hidesTransferFromReceiverAndNonParticipantOnCancel() {
        Transfer existing = persisted(transfer(10_000L, "KRW", null));
        when(transferRepository.cancelRequested(eq(existing.getId()), any(UUID.class), any(Instant.class)))
                .thenReturn(0);
        when(transferRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        var receiver = assertThrows(BusinessException.class,
                () -> transferService.cancel(RECEIVER_ID, existing.getId()));
        var otherUser = assertThrows(BusinessException.class,
                () -> transferService.cancel(OTHER_USER_ID, existing.getId()));

        assertSame(TransferErrorCode.NOT_FOUND, receiver.getErrorCode());
        assertSame(TransferErrorCode.NOT_FOUND, otherUser.getErrorCode());
        assertEquals(TransferStatus.REQUESTED, existing.getStatus());
    }

    @Test
    void returnsNotFoundWhenCancellingMissingTransfer() {
        UUID missingId = UUID.randomUUID();
        when(transferRepository.cancelRequested(eq(missingId), eq(SENDER_ID), any(Instant.class))).thenReturn(0);
        when(transferRepository.findById(missingId)).thenReturn(Optional.empty());

        var exception = assertThrows(BusinessException.class,
                () -> transferService.cancel(SENDER_ID, missingId));

        assertSame(TransferErrorCode.NOT_FOUND, exception.getErrorCode());
    }

    /** 1차에는 취소할 수 없는 상태가 없으므로, UPDATE 대상이 아니었는데 취소 상태도 아닌 경우로 2차의 처리 중·완료 상태를 대신합니다. */
    @Test
    void rejectsTransferThatWasNotCancelledAndIsNotAlreadyCancelled() {
        Transfer existing = persisted(transfer(10_000L, "KRW", null));
        when(transferRepository.cancelRequested(eq(existing.getId()), eq(SENDER_ID), any(Instant.class)))
                .thenReturn(0);
        when(transferRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        var exception = assertThrows(BusinessException.class,
                () -> transferService.cancel(SENDER_ID, existing.getId()));

        assertSame(CommonErrorCode.CONFLICT, exception.getErrorCode());
    }

    @Test
    void listsBothDirectionsNewestFirstWithCommonPageResponse() {
        Transfer transfer = persisted(transfer(1_000L, "KRW", null));
        when(transferRepository.findByParticipant(eq(SENDER_ID), eq(true), eq(true), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(transfer), invocation.getArgument(3), 41));

        var page = transferService.list(SENDER_ID, null, 1, 20);

        Pageable pageable = capturePageable(SENDER_ID, true, true);
        assertEquals(1, pageable.getPageNumber());
        assertEquals(20, pageable.getPageSize());
        assertEquals(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")), pageable.getSort());
        assertEquals(1, page.page());
        assertEquals(41, page.totalElements());
        assertEquals(3, page.totalPages());
        assertEquals(TransferDirection.SENT, page.items().getFirst().direction());
    }

    @Test
    void filtersByDirectionAndLimitsPageSizeToMaximum() {
        when(transferRepository.findByParticipant(eq(RECEIVER_ID), eq(false), eq(true), any(Pageable.class)))
                .thenAnswer(invocation -> Page.empty(invocation.getArgument(3)));

        var page = transferService.list(RECEIVER_ID, TransferDirection.RECEIVED, 0, 500);

        assertEquals(100, capturePageable(RECEIVER_ID, false, true).getPageSize());
        assertTrue(page.items().isEmpty());
    }

    @Test
    void filtersSentOnly() {
        when(transferRepository.findByParticipant(eq(SENDER_ID), eq(true), eq(false), any(Pageable.class)))
                .thenAnswer(invocation -> Page.empty(invocation.getArgument(3)));

        transferService.list(SENDER_ID, TransferDirection.SENT, 0, 20);

        capturePageable(SENDER_ID, true, false);
    }

    @ParameterizedTest
    @CsvSource({"-1, 20", "0, 0"})
    void rejectsInvalidPageOrSize(int page, int size) {
        var exception = assertThrows(BusinessException.class,
                () -> transferService.list(SENDER_ID, null, page, size));

        assertSame(CommonErrorCode.INVALID_REQUEST, exception.getErrorCode());
        verifyNoInteractions(transferRepository);
    }

    private Pageable capturePageable(UUID userId, boolean includeSent, boolean includeReceived) {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(transferRepository).findByParticipant(
                eq(userId), eq(includeSent), eq(includeReceived), captor.capture());
        return captor.getValue();
    }

    private void givenActiveWallets() {
        when(walletReader.findActiveWalletId(SENDER_ID)).thenReturn(Optional.of(SOURCE_WALLET_ID));
        when(walletReader.findActiveWalletId(RECEIVER_ID)).thenReturn(Optional.of(TARGET_WALLET_ID));
    }

    private static TransferCreateRequest request(long amount, String currency, String memo) {
        return new TransferCreateRequest(RECEIVER_ID, amount, currency, memo);
    }

    private static Transfer transfer(long amount, String currency, String memo) {
        return Transfer.request(SENDER_ID, RECEIVER_ID, SOURCE_WALLET_ID, TARGET_WALLET_ID,
                amount, currency, memo, KEY, TransferRequestHasher.hash(RECEIVER_ID, amount, currency, memo));
    }

    /** JPA 저장 시 채워지는 ID와 생성 시각을 흉내 냅니다. */
    private static Transfer persisted(Transfer transfer) {
        ReflectionTestUtils.setField(transfer, "id", UUID.randomUUID());
        ReflectionTestUtils.invokeMethod(transfer, "onCreate");
        return transfer;
    }
}
