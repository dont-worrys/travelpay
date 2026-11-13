package com.travelpay.payment.application.merchant;

import com.travelpay.common.error.BusinessException;
import com.travelpay.payment.application.merchant.dto.SettlementResolveResult;
import com.travelpay.payment.application.merchant.dto.SettlementReviewDetailResult;
import com.travelpay.payment.application.payment.AutoResolveOutcome;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerClient;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerLookup;
import com.travelpay.payment.domain.merchant.Merchant;
import com.travelpay.payment.domain.merchant.MerchantRepository;
import com.travelpay.payment.domain.merchant.Settlement;
import com.travelpay.payment.domain.merchant.SettlementItemRepository;
import com.travelpay.payment.domain.merchant.SettlementRepository;
import com.travelpay.payment.domain.merchant.SettlementReviewResolution;
import com.travelpay.payment.domain.merchant.SettlementReviewResolutionRepository;
import com.travelpay.payment.domain.merchant.SettlementStatus;
import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import com.travelpay.payment.domain.payment.ReviewActorType;
import com.travelpay.payment.domain.payment.ReviewLedgerStatus;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementReviewServiceTest {

    private static final UUID SETTLEMENT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID MERCHANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OWNER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OWNER_WALLET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID MASTER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID LEDGER_TRANSACTION_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final long AMOUNT = 15_000L;

    private static final List<PaymentLedgerLookup.Entry> EXPECTED_ENTRIES = List.of(
            new PaymentLedgerLookup.Entry(MERCHANT_ID, "KRW", "DEBIT", AMOUNT, "SETTLEMENT_OUT"),
            new PaymentLedgerLookup.Entry(OWNER_WALLET_ID, "KRW", "CREDIT", AMOUNT, "SETTLEMENT_IN"));

    private SettlementRepository settlementRepository;
    private SettlementItemRepository settlementItemRepository;
    private SettlementReviewResolutionRepository resolutionRepository;
    private PaymentLedgerClient ledgerClient;
    private SettlementReviewService service;

    @BeforeEach
    void setUp() {
        settlementRepository = mock(SettlementRepository.class);
        settlementItemRepository = mock(SettlementItemRepository.class);
        resolutionRepository = mock(SettlementReviewResolutionRepository.class);
        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        ledgerClient = mock(PaymentLedgerClient.class);
        service = new SettlementReviewService(settlementRepository, settlementItemRepository, resolutionRepository,
                merchantRepository, ledgerClient, TransactionOperations.withoutTransaction());

        Merchant merchant = mock(Merchant.class);
        when(merchant.getLedgerWalletId()).thenReturn(MERCHANT_ID);
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(settlementItemRepository.countBySettlementIds(List.of(SETTLEMENT_ID))).thenReturn(Map.of(SETTLEMENT_ID, 2L));
    }

    // 처리 규칙: 멈춘 사유 × 원장 조회 결과

    static Stream<Arguments> rules() {
        PaymentLedgerLookup posted = PaymentLedgerLookup.posted(LEDGER_TRANSACTION_ID, EXPECTED_ENTRIES);
        PaymentLedgerLookup rejected = PaymentLedgerLookup.rejected(LEDGER_TRANSACTION_ID, "가맹점 잔액이 부족합니다.");
        PaymentLedgerLookup notFound = PaymentLedgerLookup.notFound();
        return Stream.of(
                Arguments.of(SettlementReviewReason.RETRY_EXHAUSTED, posted, SettlementStatus.PAID, null),
                Arguments.of(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, posted, SettlementStatus.PAID, null),
                Arguments.of(SettlementReviewReason.RETRY_EXHAUSTED, rejected, SettlementStatus.FAILED,
                        "가맹점 잔액이 부족합니다."),
                Arguments.of(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, rejected, SettlementStatus.FAILED,
                        SettlementReviewRules.ERROR_REASON),
                Arguments.of(SettlementReviewReason.RETRY_EXHAUSTED, notFound, SettlementStatus.FAILED,
                        SettlementReviewRules.TIMEOUT_REASON),
                Arguments.of(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, notFound, SettlementStatus.FAILED,
                        SettlementReviewRules.ERROR_REASON));
    }

    @ParameterizedTest
    @MethodSource("rules")
    void resolvesByReviewReasonAndLedgerResult(SettlementReviewReason reason, PaymentLedgerLookup ledger,
                                               SettlementStatus expectedStatus, String expectedFailureReason) {
        Settlement settlement = givenManualReview(reason);
        when(ledgerClient.findSettlementTransaction(SETTLEMENT_ID)).thenReturn(ledger);
        // 재시도 소진 + 기록 없음은 처리할 때 원장 취소를 먼저 보냄(취소가 기록되면 조회 때와 같이 기록 없음)
        lenient().when(ledgerClient.cancelSettlementTransaction(SETTLEMENT_ID, MASTER_ID))
                .thenReturn(PaymentLedgerLookup.notFound());

        // 단건 조회의 예상 결과와 실제 처리 결과가 같아야 함
        SettlementReviewDetailResult detail = service.getReview(MASTER_ID, SETTLEMENT_ID);
        SettlementResolveResult result = service.resolve(MASTER_ID, SETTLEMENT_ID, "확인함");

        assertTrue(detail.expected().resolvable());
        assertEquals(expectedStatus, detail.expected().status());
        assertEquals(expectedStatus, result.status());
        assertEquals(expectedStatus, settlement.getStatus());
        assertEquals(expectedFailureReason, settlement.getFailureReason());
        assertNull(settlement.getReviewReason());
        assertEquals(ledger.status(), result.ledgerStatus());

        ArgumentCaptor<SettlementReviewResolution> saved = ArgumentCaptor.forClass(SettlementReviewResolution.class);
        verify(resolutionRepository).save(saved.capture());
        assertEquals(reason, saved.getValue().getReviewReason());
        assertEquals(expectedStatus, saved.getValue().getToStatus());
        assertEquals(MASTER_ID, saved.getValue().getActorId());
        assertEquals("확인함", saved.getValue().getMemo());
        assertEquals(detail.expected().description(), saved.getValue().getDescription());
    }

    @Test
    void paidKeepsItemsAndStoresLedgerTransaction() {
        Settlement settlement = givenManualReview(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findSettlementTransaction(SETTLEMENT_ID))
                .thenReturn(PaymentLedgerLookup.posted(LEDGER_TRANSACTION_ID, EXPECTED_ENTRIES));

        service.resolve(MASTER_ID, SETTLEMENT_ID, null);

        assertEquals(LEDGER_TRANSACTION_ID, settlement.getLedgerTransactionId());
        verify(settlementItemRepository, never()).deleteAllBySettlementId(any());
    }

    // 원장에 취소를 기록한 뒤에만 항목을 풀어 줌. 늦게 온 마지막 요청이 반영되지 않으므로 다음 정산에서 이중 지급이 없음
    @Test
    void failedReleasesPaymentsForNextSettlement() {
        givenManualReview(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findSettlementTransaction(SETTLEMENT_ID)).thenReturn(PaymentLedgerLookup.notFound());
        when(ledgerClient.cancelSettlementTransaction(SETTLEMENT_ID, MASTER_ID))
                .thenReturn(PaymentLedgerLookup.notFound());

        service.resolve(MASTER_ID, SETTLEMENT_ID, null);

        verify(ledgerClient).cancelSettlementTransaction(SETTLEMENT_ID, MASTER_ID);
        verify(settlementItemRepository).deleteAllBySettlementId(SETTLEMENT_ID);
    }

    // 조회와 취소 사이에 마지막 요청이 늦게 커밋되면 그 거래로 지급 완료, 항목은 유지
    @Test
    void paysWhenLateCommitWinsOverCancel() {
        Settlement settlement = givenManualReview(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findSettlementTransaction(SETTLEMENT_ID)).thenReturn(PaymentLedgerLookup.notFound());
        when(ledgerClient.cancelSettlementTransaction(SETTLEMENT_ID, MASTER_ID))
                .thenReturn(PaymentLedgerLookup.posted(LEDGER_TRANSACTION_ID, EXPECTED_ENTRIES));

        SettlementResolveResult result = service.resolve(MASTER_ID, SETTLEMENT_ID, null);

        assertEquals(SettlementStatus.PAID, result.status());
        assertEquals(SettlementStatus.PAID, settlement.getStatus());
        assertEquals(LEDGER_TRANSACTION_ID, settlement.getLedgerTransactionId());
        verify(settlementItemRepository, never()).deleteAllBySettlementId(any());
    }

    // 취소됐는지 모르면 확정하지 않음(항목도 그대로). 다시 처리하면 같은 취소를 다시 보냄
    @Test
    void keepsSettlementWhenLedgerCancelFails() {
        Settlement settlement = givenManualReview(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findSettlementTransaction(SETTLEMENT_ID)).thenReturn(PaymentLedgerLookup.notFound());
        when(ledgerClient.cancelSettlementTransaction(SETTLEMENT_ID, MASTER_ID))
                .thenThrow(new BusinessException(PaymentErrorCode.LEDGER_LOOKUP_UNAVAILABLE));

        assertError(PaymentErrorCode.LEDGER_LOOKUP_UNAVAILABLE, () -> service.resolve(MASTER_ID, SETTLEMENT_ID, null));

        assertEquals(SettlementStatus.MANUAL_REVIEW, settlement.getStatus());
        verify(settlementRepository, never()).save(any());
        verify(settlementItemRepository, never()).deleteAllBySettlementId(any());
        verify(resolutionRepository, never()).save(any());
    }

    // 요청 거부는 원장이 이미 4xx로 답해 늦은 커밋이 없으므로 취소하지 않고 FAILED
    @Test
    void doesNotCancelNotAcceptedSettlement() {
        Settlement settlement = givenManualReview(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED);
        when(ledgerClient.findSettlementTransaction(SETTLEMENT_ID)).thenReturn(PaymentLedgerLookup.notFound());

        service.resolve(MASTER_ID, SETTLEMENT_ID, null);

        assertEquals(SettlementStatus.FAILED, settlement.getStatus());
        verify(ledgerClient, never()).cancelSettlementTransaction(any(), any());
    }

    // 처리할 수 없는 경우

    @Test
    void differentLedgerEntriesRequireCorrection() {
        Settlement settlement = givenManualReview(SettlementReviewReason.RETRY_EXHAUSTED);
        List<PaymentLedgerLookup.Entry> otherWallet = List.of(
                new PaymentLedgerLookup.Entry(MERCHANT_ID, "KRW", "DEBIT", AMOUNT, "SETTLEMENT_OUT"),
                new PaymentLedgerLookup.Entry(UUID.randomUUID(), "KRW", "CREDIT", AMOUNT, "SETTLEMENT_IN"));
        when(ledgerClient.findSettlementTransaction(SETTLEMENT_ID))
                .thenReturn(PaymentLedgerLookup.posted(LEDGER_TRANSACTION_ID, otherWallet));

        SettlementReviewDetailResult detail = service.getReview(MASTER_ID, SETTLEMENT_ID);

        assertFalse(detail.expected().resolvable());
        assertNull(detail.expected().status());
        assertError(PaymentErrorCode.LEDGER_CORRECTION_REQUIRED, () -> service.resolve(MASTER_ID, SETTLEMENT_ID, null));
        assertEquals(SettlementStatus.MANUAL_REVIEW, settlement.getStatus());
        verify(resolutionRepository, never()).save(any());
    }

    @Test
    void ledgerLookupFailureShowsDetailButResolveIsUnavailable() {
        Settlement settlement = givenManualReview(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findSettlementTransaction(SETTLEMENT_ID))
                .thenThrow(new BusinessException(PaymentErrorCode.LEDGER_LOOKUP_UNAVAILABLE));

        SettlementReviewDetailResult detail = service.getReview(MASTER_ID, SETTLEMENT_ID);

        assertTrue(detail.ledgerLookupFailed());
        assertNull(detail.expected());
        assertEquals(2, detail.paymentCount());
        assertError(PaymentErrorCode.LEDGER_LOOKUP_UNAVAILABLE, () -> service.resolve(MASTER_ID, SETTLEMENT_ID, null));
        assertEquals(SettlementStatus.MANUAL_REVIEW, settlement.getStatus());
    }

    @Test
    void settlementNotInManualReviewIsRejected() {
        Settlement requested = settlement();
        when(settlementRepository.findById(SETTLEMENT_ID)).thenReturn(Optional.of(requested));

        assertError(PaymentErrorCode.NOT_MANUAL_REVIEW, () -> service.getReview(MASTER_ID, SETTLEMENT_ID));
        assertError(PaymentErrorCode.NOT_MANUAL_REVIEW, () -> service.resolve(MASTER_ID, SETTLEMENT_ID, null));
        verify(ledgerClient, never()).findSettlementTransaction(any());
    }

    @Test
    void unknownSettlementIsNotFound() {
        when(settlementRepository.findById(SETTLEMENT_ID)).thenReturn(Optional.empty());

        assertError(PaymentErrorCode.SETTLEMENT_NOT_FOUND, () -> service.resolve(MASTER_ID, SETTLEMENT_ID, null));
    }

    @Test
    void concurrentResolutionIsConflict() {
        givenManualReview(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findSettlementTransaction(SETTLEMENT_ID))
                .thenReturn(PaymentLedgerLookup.posted(LEDGER_TRANSACTION_ID, EXPECTED_ENTRIES));
        when(settlementRepository.save(any(Settlement.class)))
                .thenThrow(new OptimisticLockingFailureException("다른 처리가 먼저 끝남"));

        assertError(PaymentErrorCode.NOT_MANUAL_REVIEW, () -> service.resolve(MASTER_ID, SETTLEMENT_ID, null));
        verify(resolutionRepository, never()).save(any());
    }

    // 자동 처리(스케줄러)

    @Test
    void autoPaysPostedSettlementBySystem() {
        Settlement settlement = givenManualReview(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findSettlementTransaction(SETTLEMENT_ID))
                .thenReturn(PaymentLedgerLookup.posted(LEDGER_TRANSACTION_ID, EXPECTED_ENTRIES));

        assertEquals(AutoResolveOutcome.RESOLVED, service.autoResolve(settlement));

        assertEquals(SettlementStatus.PAID, settlement.getStatus());
        verify(settlementItemRepository, never()).deleteAllBySettlementId(any());
        SettlementReviewResolution resolution = savedResolution();
        assertEquals(ReviewActorType.SYSTEM, resolution.getActorType());
        assertNull(resolution.getActorId());
        assertNull(resolution.getMemo());
        assertEquals("자동 처리: 재시도 한도 초과, 원장 반영 확인 → 정산 지급", resolution.getDescription());
    }

    @Test
    void autoFailsRejectedSettlementAndReleasesPayments() {
        Settlement settlement = givenManualReview(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED);
        when(ledgerClient.findSettlementTransaction(SETTLEMENT_ID))
                .thenReturn(PaymentLedgerLookup.rejected(LEDGER_TRANSACTION_ID, "가맹점 잔액이 부족합니다."));

        assertEquals(AutoResolveOutcome.RESOLVED, service.autoResolve(settlement));

        assertEquals(SettlementStatus.FAILED, settlement.getStatus());
        assertEquals(SettlementReviewRules.ERROR_REASON, settlement.getFailureReason());
        verify(settlementItemRepository).deleteAllBySettlementId(SETTLEMENT_ID);
        assertEquals(ReviewActorType.SYSTEM, savedResolution().getActorType());
    }

    // 원장 기록이 없으면 원장 취소(원장에 쓰기)가 필요해 운영자에게 남김
    @ParameterizedTest
    @EnumSource(SettlementReviewReason.class)
    void autoSkipsSettlementMissingFromLedgerWithoutCancelling(SettlementReviewReason reason) {
        Settlement settlement = givenManualReview(reason);
        when(ledgerClient.findSettlementTransaction(SETTLEMENT_ID)).thenReturn(PaymentLedgerLookup.notFound());

        assertEquals(AutoResolveOutcome.SKIPPED, service.autoResolve(settlement));

        assertEquals(SettlementStatus.MANUAL_REVIEW, settlement.getStatus());
        verify(ledgerClient, never()).cancelSettlementTransaction(any(), any());
        verify(settlementRepository, never()).save(any());
        verify(resolutionRepository, never()).save(any());
    }

    @Test
    void autoSkipsSettlementWhenLedgerEntriesDiffer() {
        Settlement settlement = givenManualReview(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findSettlementTransaction(SETTLEMENT_ID)).thenReturn(PaymentLedgerLookup.posted(
                LEDGER_TRANSACTION_ID, List.of(
                        new PaymentLedgerLookup.Entry(MERCHANT_ID, "KRW", "DEBIT", 1L, "SETTLEMENT_OUT"),
                        new PaymentLedgerLookup.Entry(OWNER_WALLET_ID, "KRW", "CREDIT", 1L, "SETTLEMENT_IN"))));

        assertEquals(AutoResolveOutcome.SKIPPED, service.autoResolve(settlement));

        assertEquals(SettlementStatus.MANUAL_REVIEW, settlement.getStatus());
        verify(resolutionRepository, never()).save(any());
    }

    @Test
    void autoReportsAlreadyResolvedWhenOperatorWonTheRace() {
        Settlement settlement = givenManualReview(SettlementReviewReason.RETRY_EXHAUSTED);
        when(ledgerClient.findSettlementTransaction(SETTLEMENT_ID))
                .thenReturn(PaymentLedgerLookup.posted(LEDGER_TRANSACTION_ID, EXPECTED_ENTRIES));
        when(settlementRepository.save(any(Settlement.class)))
                .thenThrow(new OptimisticLockingFailureException("다른 처리가 먼저 끝남"));

        assertEquals(AutoResolveOutcome.ALREADY_RESOLVED, service.autoResolve(settlement));
        verify(resolutionRepository, never()).save(any());
    }

    private SettlementReviewResolution savedResolution() {
        ArgumentCaptor<SettlementReviewResolution> captor = ArgumentCaptor.forClass(SettlementReviewResolution.class);
        verify(resolutionRepository).save(captor.capture());
        return captor.getValue();
    }

    private Settlement givenManualReview(SettlementReviewReason reason) {
        Settlement settlement = settlement();
        ReflectionTestUtils.setField(settlement, "status", SettlementStatus.MANUAL_REVIEW);
        ReflectionTestUtils.setField(settlement, "reviewReason", reason);
        when(settlementRepository.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement));
        return settlement;
    }

    private static Settlement settlement() {
        Settlement settlement = Settlement.request(MERCHANT_ID, OWNER_ID, "key", OWNER_ID, OWNER_WALLET_ID,
                CurrencyCode.KRW, AMOUNT, Instant.now());
        ReflectionTestUtils.setField(settlement, "id", SETTLEMENT_ID);
        return settlement;
    }

    private static void assertError(PaymentErrorCode expected, Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertEquals(expected, exception.getErrorCode());
    }
}
