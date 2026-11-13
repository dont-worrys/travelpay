package com.travelpay.payment.transfer.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.payment.transfer.client.TransferLedgerClient;
import com.travelpay.payment.transfer.client.TransferLedgerException;
import com.travelpay.payment.transfer.client.TransferUserReader;
import com.travelpay.payment.transfer.client.TransferWalletReader;
import com.travelpay.payment.transfer.client.UserRecipientResponse;
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
import org.junit.jupiter.params.provider.EnumSource;
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
import static org.mockito.Mockito.times;
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
    private TransferUserReader userReader;
    private TransferLedgerClient ledgerClient;
    private TransferService transferService;
    private final Map<UUID, Transfer> stored = new HashMap<>();

    @BeforeEach
    void setUp() {
        transferRepository = mock(TransferRepository.class);
        walletReader = mock(TransferWalletReader.class);
        userReader = mock(TransferUserReader.class);
        // 원장 결과에 따른 흐름은 TransferLedgerFlowTest에서 검증합니다. 여기서는 서킷이 열려 보내지 못한 것으로 두어
        // 저장한 PROCESSING 송금이 그대로 응답되게 합니다.
        ledgerClient = mock(TransferLedgerClient.class);
        when(ledgerClient.register(any())).thenThrow(TransferLedgerException.notSent("test"));
        when(transferRepository.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0))));
        transferService = new TransferService(transferRepository, walletReader, userReader, ledgerClient);
    }

    @Test
    void savesValidTransferAsProcessingAndSendsItToLedger() {
        givenActiveWallets();
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.empty());
        givenSaveIsReadBack();

        var response = transferService.request(SENDER_ID, KEY, request(10_000L, "KRW", "  저녁값  "));

        assertEquals(TransferStatus.PROCESSING, response.status());
        assertEquals(TransferDirection.SENT, response.direction());
        assertEquals(10_000L, response.amount());
        assertEquals("저녁값", response.memo());
        verify(transferRepository).saveAndFlush(any(Transfer.class));
        verify(ledgerClient, times(1)).register(any());
        verify(transferRepository, never()).finishProcessing(any(), any(), any(), any());
    }

    @Test
    void storesBothWalletIdsAndNormalizedMemo() {
        givenActiveWallets();
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.empty());
        List<Transfer> saved = new ArrayList<>();
        when(transferRepository.saveAndFlush(any(Transfer.class))).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return save(invocation.getArgument(0));
        });

        transferService.request(SENDER_ID, KEY, request(10_000L, "KRW", "   "));

        Transfer transfer = saved.getFirst();
        assertEquals(SOURCE_WALLET_ID, transfer.getSourceWalletId());
        assertEquals(TARGET_WALLET_ID, transfer.getTargetWalletId());
        assertNull(transfer.getMemo());
    }

    @Test
    void returnsExistingTransferForSameKeyAndSameRequestWithoutCallingWallet() {
        Transfer existing = persisted(transfer(10_000L, "KRW", null));
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.of(existing));

        var response = transferService.request(SENDER_ID, KEY, request(10_000L, "KRW", null));

        assertEquals(existing.getId(), response.transferId());
        verifyNoInteractions(walletReader, userReader, ledgerClient);
        verify(transferRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"receiverId", "amount", "currency", "memo", "memoRemoved"})
    void rejectsSameKeyWhenAnyStoredRequestFieldDiffers(String field) {
        Transfer existing = persisted(transfer(10_000L, "KRW", "저녁값"));
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.of(existing));
        TransferCreateRequest changed = switch (field) {
            case "receiverId" -> new TransferCreateRequest(UUID.randomUUID(), 10_000L, "KRW", "저녁값");
            case "amount" -> request(10_001L, "KRW", "저녁값");
            case "currency" -> request(10_000L, "JPY", "저녁값");
            case "memo" -> request(10_000L, "KRW", "점심값");
            case "memoRemoved" -> request(10_000L, "KRW", null);
            default -> throw new IllegalArgumentException(field);
        };

        var exception = assertThrows(BusinessException.class,
                () -> transferService.request(SENDER_ID, KEY, changed));

        assertSame(TransferErrorCode.IDEMPOTENCY_KEY_REUSED, exception.getErrorCode());
        verify(transferRepository, never()).saveAndFlush(any());
        verifyNoInteractions(walletReader, userReader);
    }

    @ParameterizedTest
    @ValueSource(strings = {"저녁값", "  저녁값  ", "\t저녁값\n"})
    void treatsMemoWithOnlySurroundingWhitespaceAsSameRequest(String memo) {
        Transfer existing = persisted(transfer(10_000L, "KRW", "저녁값"));
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.of(existing));

        var response = transferService.request(SENDER_ID, KEY, request(10_000L, "KRW", memo));

        assertEquals(existing.getId(), response.transferId());
        verify(transferRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void treatsBlankMemoAsSameRequestAsMissingMemo(String memo) {
        Transfer existing = persisted(transfer(10_000L, "KRW", null));
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.of(existing));

        var response = transferService.request(SENDER_ID, KEY, request(10_000L, "KRW", memo));

        assertEquals(existing.getId(), response.transferId());
    }

    @Test
    void rejectsConcurrentlySavedTransferWithSameKeyButDifferentRequest() {
        givenActiveWallets();
        Transfer concurrent = persisted(transfer(10_000L, "KRW", null));
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrent));
        when(transferRepository.saveAndFlush(any(Transfer.class)))
                .thenThrow(new DataIntegrityViolationException("uk_transfer_sender_id_idempotency_key"));

        var exception = assertThrows(BusinessException.class,
                () -> transferService.request(SENDER_ID, KEY, request(20_000L, "KRW", null)));

        assertSame(TransferErrorCode.IDEMPOTENCY_KEY_REUSED, exception.getErrorCode());
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
        verifyNoInteractions(transferRepository, walletReader, userReader);
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
        verifyNoInteractions(walletReader, userReader);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "EUR"})
    void rejectsUnsupportedCurrency(String currency) {
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.empty());

        var exception = assertThrows(BusinessException.class,
                () -> transferService.request(SENDER_ID, KEY, request(10_000L, currency, null)));

        assertSame(TransferErrorCode.UNSUPPORTED_CURRENCY, exception.getErrorCode());
        verifyNoInteractions(walletReader, userReader);
    }

    @Test
    void rejectsWhenSenderHasNoActiveWallet() {
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.empty());
        when(walletReader.findActiveWalletId(SENDER_ID)).thenReturn(Optional.empty());

        var exception = assertThrows(BusinessException.class,
                () -> transferService.request(SENDER_ID, KEY, request(10_000L, "KRW", null)));

        assertSame(TransferErrorCode.SENDER_WALLET_UNAVAILABLE, exception.getErrorCode());
        verify(transferRepository, never()).saveAndFlush(any());
        verifyNoInteractions(userReader);
    }

    @Test
    void rejectsWhenReceiverIsNotActiveMemberWithoutCheckingReceiverWallet() {
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.empty());
        when(walletReader.findActiveWalletId(SENDER_ID)).thenReturn(Optional.of(SOURCE_WALLET_ID));
        when(userReader.findRecipient(RECEIVER_ID)).thenReturn(Optional.empty());

        var exception = assertThrows(BusinessException.class,
                () -> transferService.request(SENDER_ID, KEY, request(10_000L, "KRW", null)));

        assertSame(TransferErrorCode.RECEIVER_UNAVAILABLE, exception.getErrorCode());
        verify(walletReader, never()).findActiveWalletId(RECEIVER_ID);
        verify(transferRepository, never()).saveAndFlush(any());
    }

    @Test
    void propagatesUserServiceErrorOnTransferRequestWithoutSaving() {
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.empty());
        when(walletReader.findActiveWalletId(SENDER_ID)).thenReturn(Optional.of(SOURCE_WALLET_ID));
        when(userReader.findRecipient(RECEIVER_ID))
                .thenThrow(new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE));

        var exception = assertThrows(BusinessException.class,
                () -> transferService.request(SENDER_ID, KEY, request(10_000L, "KRW", null)));

        assertSame(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
        verify(transferRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsWhenReceiverHasNoActiveWallet() {
        when(transferRepository.findBySenderIdAndIdempotencyKey(SENDER_ID, KEY)).thenReturn(Optional.empty());
        when(walletReader.findActiveWalletId(SENDER_ID)).thenReturn(Optional.of(SOURCE_WALLET_ID));
        givenActiveRecipient();
        when(walletReader.findActiveWalletId(RECEIVER_ID)).thenReturn(Optional.empty());

        var exception = assertThrows(BusinessException.class,
                () -> transferService.request(SENDER_ID, KEY, request(10_000L, "KRW", null)));

        assertSame(TransferErrorCode.RECEIVER_UNAVAILABLE, exception.getErrorCode());
        verify(transferRepository, never()).saveAndFlush(any());
    }

    @Test
    void showsRecipientWithActiveWalletAsTransferableWithMaskedName() {
        givenActiveRecipient();
        when(walletReader.findWallet(RECEIVER_ID)).thenReturn(Optional.of(new WalletResponse(TARGET_WALLET_ID, "ACTIVE")));

        var response = transferService.findRecipient(SENDER_ID, RECEIVER_ID);

        assertEquals(RECEIVER_ID, response.receiverId());
        assertEquals("홍*동", response.receiverName());
        assertTrue(response.transferable());
        verifyNoInteractions(transferRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SUSPENDED", "CLOSED"})
    void showsRecipientWithNonActiveWalletAsNotTransferable(String status) {
        givenActiveRecipient();
        when(walletReader.findWallet(RECEIVER_ID)).thenReturn(Optional.of(new WalletResponse(TARGET_WALLET_ID, status)));

        assertFalse(transferService.findRecipient(SENDER_ID, RECEIVER_ID).transferable());
    }

    @Test
    void returnsNotFoundForRecipientWhoIsNotActiveMemberWithoutCheckingWallet() {
        when(userReader.findRecipient(RECEIVER_ID)).thenReturn(Optional.empty());

        var exception = assertThrows(BusinessException.class,
                () -> transferService.findRecipient(SENDER_ID, RECEIVER_ID));

        assertSame(TransferErrorCode.RECEIVER_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(walletReader);
    }

    @Test
    void returnsNotFoundForRecipientWithoutWallet() {
        givenActiveRecipient();
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
        verifyNoInteractions(walletReader, userReader);
    }

    @Test
    void propagatesUserServiceErrorOnRecipientLookup() {
        when(userReader.findRecipient(RECEIVER_ID))
                .thenThrow(new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE));

        var exception = assertThrows(BusinessException.class,
                () -> transferService.findRecipient(SENDER_ID, RECEIVER_ID));

        assertSame(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
        verifyNoInteractions(walletReader);
    }

    @Test
    void propagatesWalletServiceErrorOnRecipientLookup() {
        givenActiveRecipient();
        when(walletReader.findWallet(RECEIVER_ID))
                .thenThrow(new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE));

        var exception = assertThrows(BusinessException.class,
                () -> transferService.findRecipient(SENDER_ID, RECEIVER_ID));

        assertSame(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void showsCompletedTransferToSenderAndReceiverWithTheirDirection() {
        Transfer existing = persisted(transfer(10_000L, "KRW", null));
        ReflectionTestUtils.setField(existing, "status", TransferStatus.COMPLETED);
        when(transferRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertEquals(TransferDirection.SENT, transferService.get(SENDER_ID, existing.getId()).direction());
        assertEquals(TransferDirection.RECEIVED, transferService.get(RECEIVER_ID, existing.getId()).direction());
    }

    @ParameterizedTest
    @EnumSource(value = TransferStatus.class, names = "COMPLETED", mode = EnumSource.Mode.EXCLUDE)
    void showsUnfinishedTransferOnlyToSenderAndHidesItFromReceiverAsNotFound(TransferStatus status) {
        Transfer existing = persisted(transfer(10_000L, "KRW", null));
        ReflectionTestUtils.setField(existing, "status", status);
        when(transferRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        var receiver = assertThrows(BusinessException.class,
                () -> transferService.get(RECEIVER_ID, existing.getId()));

        assertEquals(status, transferService.get(SENDER_ID, existing.getId()).status());
        assertSame(TransferErrorCode.NOT_FOUND, receiver.getErrorCode());
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

    /** 저장한 송금을 원장 호출 뒤 다시 읽을 때 같은 송금이 나오게 합니다. */
    private void givenSaveIsReadBack() {
        when(transferRepository.saveAndFlush(any(Transfer.class)))
                .thenAnswer(invocation -> save(invocation.getArgument(0)));
    }

    private Transfer save(Transfer transfer) {
        Transfer persisted = persisted(transfer);
        stored.put(persisted.getId(), persisted);
        return persisted;
    }

    private void givenActiveWallets() {
        when(walletReader.findActiveWalletId(SENDER_ID)).thenReturn(Optional.of(SOURCE_WALLET_ID));
        when(walletReader.findActiveWalletId(RECEIVER_ID)).thenReturn(Optional.of(TARGET_WALLET_ID));
        givenActiveRecipient();
    }

    private void givenActiveRecipient() {
        when(userReader.findRecipient(RECEIVER_ID))
                .thenReturn(Optional.of(new UserRecipientResponse(RECEIVER_ID, "홍길동")));
    }

    private static TransferCreateRequest request(long amount, String currency, String memo) {
        return new TransferCreateRequest(RECEIVER_ID, amount, currency, memo);
    }

    private static Transfer transfer(long amount, String currency, String memo) {
        return Transfer.process(SENDER_ID, RECEIVER_ID, SOURCE_WALLET_ID, TARGET_WALLET_ID,
                amount, currency, memo, KEY);
    }

    /** JPA 저장 시 채워지는 ID와 생성 시각을 흉내 냅니다. */
    private static Transfer persisted(Transfer transfer) {
        ReflectionTestUtils.setField(transfer, "id", UUID.randomUUID());
        ReflectionTestUtils.invokeMethod(transfer, "onCreate");
        return transfer;
    }
}
