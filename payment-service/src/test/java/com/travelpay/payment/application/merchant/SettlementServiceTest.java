package com.travelpay.payment.application.merchant;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.payment.application.payment.PaymentService;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerClient;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerResult;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerSettlementCommand;
import com.travelpay.payment.application.payment.client.wallet.PaymentWallet;
import com.travelpay.payment.application.payment.client.wallet.PaymentWalletClient;
import com.travelpay.payment.application.merchant.dto.SettlementPreviewResult;
import com.travelpay.payment.application.merchant.dto.SettlementResult;
import com.travelpay.payment.domain.merchant.Merchant;
import com.travelpay.payment.domain.merchant.MerchantRepository;
import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentDeclinedException;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import com.travelpay.payment.domain.payment.PaymentRepository;
import com.travelpay.payment.domain.payment.Refund;
import com.travelpay.payment.domain.payment.RefundRepository;
import com.travelpay.payment.domain.payment.SettlementCandidate;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
import com.travelpay.payment.domain.merchant.Settlement;
import com.travelpay.payment.domain.merchant.SettlementItem;
import com.travelpay.payment.domain.merchant.SettlementItemRepository;
import com.travelpay.payment.domain.merchant.SettlementRepository;
import com.travelpay.payment.domain.merchant.SettlementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementServiceTest {

    private static final UUID MERCHANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_MERCHANT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID OWNER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MASTER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID STRANGER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_WALLET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID SETTLEMENT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID PAYMENT_A = UUID.fromString("88888888-8888-8888-8888-888888888881");
    private static final UUID PAYMENT_B = UUID.fromString("88888888-8888-8888-8888-888888888882");
    private static final UUID PAYMENT_C = UUID.fromString("88888888-8888-8888-8888-888888888883");
    private static final UUID LEDGER_TRANSACTION_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String KEY = "settlement-key-1";
    private static final AuthenticatedUser OWNER = new AuthenticatedUser(OWNER_ID, AuthRole.USER);
    private static final AuthenticatedUser MASTER = new AuthenticatedUser(MASTER_ID, AuthRole.MASTER);

    private SettlementRepository settlementRepository;
    private SettlementItemRepository settlementItemRepository;
    private MerchantRepository merchantRepository;
    private PaymentRepository paymentRepository;
    private RefundRepository refundRepository;
    private PaymentWalletClient walletClient;
    private PaymentLedgerClient ledgerClient;
    private SettlementService settlementService;

    @BeforeEach
    void setUp() {
        settlementRepository = mock(SettlementRepository.class);
        settlementItemRepository = mock(SettlementItemRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        refundRepository = mock(RefundRepository.class);
        walletClient = mock(PaymentWalletClient.class);
        ledgerClient = mock(PaymentLedgerClient.class);
        settlementService = new SettlementService(settlementRepository, settlementItemRepository, merchantRepository,
                paymentRepository, refundRepository, walletClient, ledgerClient,
                TransactionOperations.withoutTransaction());

        givenMerchant(MERCHANT_WALLET_ID_OF_MERCHANT);
        when(walletClient.findWallet(OWNER_ID)).thenReturn(Optional.of(new PaymentWallet(OWNER_WALLET_ID, "ACTIVE")));
        when(settlementRepository.findByRequestedByAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(invocation -> {
            Settlement settlement = invocation.getArgument(0);
            if (settlement.getId() == null) {
                ReflectionTestUtils.setField(settlement, "id", SETTLEMENT_ID);
            }
            return settlement;
        });
        when(paymentRepository.findSettlementTargets(eq(MERCHANT_ID), any(), eq(SettlementService.MAX_PAYMENTS_PER_SETTLEMENT)))
                .thenReturn(List.of(payment(PAYMENT_A, 10_000L), payment(PAYMENT_B, 5_000L)));
        when(refundRepository.findAllByPaymentIdIn(any())).thenReturn(List.of());
        when(ledgerClient.settlementTransaction(any())).thenReturn(PaymentLedgerResult.approved(LEDGER_TRANSACTION_ID));
    }

    // 가맹점 원장 지갑 ID는 가맹점 ID와 같음
    private static final UUID MERCHANT_WALLET_ID_OF_MERCHANT = MERCHANT_ID;

    // 정상 처리

    @Test
    void settlesApprovedPaymentsToOwnerWallet() {
        SettlementResult result = settlementService.requestSettlement(OWNER, MERCHANT_ID, KEY);

        assertEquals(SettlementStatus.PAID, result.status());
        assertEquals(15_000L, result.amount());
        assertEquals(2, result.paymentCount());
        assertEquals(LEDGER_TRANSACTION_ID, result.ledgerTransactionId());
        assertEquals(OWNER_ID, result.ownerUserId());

        ArgumentCaptor<PaymentLedgerSettlementCommand> command = ArgumentCaptor.forClass(PaymentLedgerSettlementCommand.class);
        verify(ledgerClient).settlementTransaction(command.capture());
        assertEquals(new PaymentLedgerSettlementCommand(SETTLEMENT_ID, OWNER_ID, MERCHANT_ID, OWNER_WALLET_ID,
                CurrencyCode.KRW, 15_000L), command.getValue());
    }

    @Test
    void itemAmountExcludesCompletedRefundsOnly() {
        Refund completed = Refund.request(PAYMENT_A, OWNER_ID, "r1", 3_000L, CurrencyCode.KRW, "부분 환불");
        completed.complete(UUID.randomUUID());
        Refund declined = Refund.request(PAYMENT_A, OWNER_ID, "r2", 2_000L, CurrencyCode.KRW, "거절된 환불");
        declined.decline("거절");
        when(refundRepository.findAllByPaymentIdIn(List.of(PAYMENT_A, PAYMENT_B))).thenReturn(List.of(completed, declined));

        SettlementResult result = settlementService.requestSettlement(OWNER, MERCHANT_ID, KEY);

        assertEquals(12_000L, result.amount());
        List<SettlementItem> items = savedItems();
        assertEquals(7_000L, items.get(0).getAmount());
        assertEquals(PAYMENT_A, items.get(0).getPaymentId());
        assertEquals(5_000L, items.get(1).getAmount());
        assertTrue(items.stream().allMatch(item -> item.getSettlementId().equals(SETTLEMENT_ID)));
    }

    @Test
    void targetsPaymentsApprovedBeforeRefundPeriodAndMargin() {
        Instant before = Instant.now();

        SettlementResult result = settlementService.requestSettlement(OWNER, MERCHANT_ID, KEY);

        ArgumentCaptor<Instant> approvedBefore = ArgumentCaptor.forClass(Instant.class);
        verify(paymentRepository).findSettlementTargets(eq(MERCHANT_ID), approvedBefore.capture(), eq(SettlementService.MAX_PAYMENTS_PER_SETTLEMENT));
        assertEquals(result.cutoffAt().minus(PaymentService.REFUND_PERIOD).minus(Duration.ofHours(1)),
                approvedBefore.getValue());
        assertTrue(!result.cutoffAt().isBefore(before));
    }

    @Test
    void masterCanRequestAndMoneyStillGoesToOwner() {
        SettlementResult result = settlementService.requestSettlement(MASTER, MERCHANT_ID, KEY);

        assertEquals(SettlementStatus.PAID, result.status());
        ArgumentCaptor<Settlement> saved = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementRepository, atLeastOnce()).save(saved.capture());
        assertEquals(MASTER_ID, saved.getValue().getRequestedBy());
        assertEquals(OWNER_ID, saved.getValue().getOwnerUserId());
        assertEquals(OWNER_WALLET_ID, saved.getValue().getOwnerWalletId());
    }

    // 요청 거절

    @Test
    void strangerGetsMerchantNotFound() {
        assertError(PaymentErrorCode.MERCHANT_NOT_FOUND,
                () -> settlementService.requestSettlement(new AuthenticatedUser(STRANGER_ID, AuthRole.USER),
                        MERCHANT_ID, KEY));
        assertError(PaymentErrorCode.MERCHANT_NOT_FOUND,
                () -> settlementService.requestSettlement(OWNER, OTHER_MERCHANT_ID, KEY));
        verify(ledgerClient, never()).settlementTransaction(any());
    }

    @Test
    void pendingMerchantCannotSettle() {
        givenMerchant(null);

        assertError(PaymentErrorCode.MERCHANT_NOT_ACTIVE,
                () -> settlementService.requestSettlement(OWNER, MERCHANT_ID, KEY));
    }

    @Test
    void ownerWithoutUsableWalletCannotSettle() {
        when(walletClient.findWallet(OWNER_ID)).thenReturn(Optional.empty());
        assertError(PaymentErrorCode.SETTLEMENT_WALLET_NOT_AVAILABLE,
                () -> settlementService.requestSettlement(OWNER, MERCHANT_ID, KEY));

        when(walletClient.findWallet(OWNER_ID)).thenReturn(Optional.of(new PaymentWallet(OWNER_WALLET_ID, "SUSPENDED")));
        assertError(PaymentErrorCode.SETTLEMENT_WALLET_NOT_AVAILABLE,
                () -> settlementService.requestSettlement(OWNER, MERCHANT_ID, KEY));

        verify(settlementRepository, never()).save(any());
    }

    @Test
    void noTargetPaymentsIsRejected() {
        when(paymentRepository.findSettlementTargets(eq(MERCHANT_ID), any(), eq(SettlementService.MAX_PAYMENTS_PER_SETTLEMENT))).thenReturn(List.of());

        assertError(PaymentErrorCode.NO_SETTLEMENT_TARGET,
                () -> settlementService.requestSettlement(OWNER, MERCHANT_ID, KEY));
        verify(settlementRepository, never()).save(any());
    }

    // 원장 결과

    @Test
    void declinedSettlementFailsAndReleasesPayments() {
        when(ledgerClient.settlementTransaction(any())).thenReturn(PaymentLedgerResult.declined("가맹점 잔액이 부족합니다."));

        PaymentDeclinedException exception = assertThrows(PaymentDeclinedException.class,
                () -> settlementService.requestSettlement(OWNER, MERCHANT_ID, KEY));

        assertEquals(PaymentErrorCode.SETTLEMENT_DECLINED, exception.getErrorCode());
        assertEquals("가맹점 잔액이 부족합니다.", exception.getDeclineReason());
        verify(settlementItemRepository).deleteAllBySettlementId(SETTLEMENT_ID);
    }

    @Test
    void unknownResultStaysRequestedAndRecordsAttempt() {
        when(ledgerClient.settlementTransaction(any())).thenReturn(PaymentLedgerResult.unknown());

        SettlementResult result = settlementService.requestSettlement(OWNER, MERCHANT_ID, KEY);

        assertEquals(SettlementStatus.REQUESTED, result.status());
        assertNull(result.ledgerTransactionId());
        verify(settlementRepository).recordUnknownAttempt(eq(SETTLEMENT_ID), eq(0), eq(SettlementStatus.REQUESTED),
                isNull(), any());
        verify(settlementItemRepository, never()).deleteAllBySettlementId(any());
    }

    @Test
    void notAcceptedStopsAsManualReviewWithoutReleasingPayments() {
        when(ledgerClient.settlementTransaction(any())).thenReturn(PaymentLedgerResult.notAccepted());
        when(settlementRepository.markManualReview(eq(SETTLEMENT_ID), eq(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED),
                any())).thenReturn(1);
        when(settlementRepository.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(manualReview(SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, 0)));

        SettlementResult result = settlementService.requestSettlement(OWNER, MERCHANT_ID, KEY);

        // 오류가 아니라 상태로 응답(결제와 같음). 원장에 반영됐을 수 있어 항목은 지우지 않음
        assertEquals(SettlementStatus.MANUAL_REVIEW, result.status());
        verify(settlementItemRepository, never()).deleteAllBySettlementId(any());
        verify(settlementRepository, never()).recordUnknownAttempt(any(), anyInt(), any(), any(), any());
    }

    @Test
    void fifthUnknownResultStopsAsManualReview() {
        Settlement fourTimesUnknown = settlementWithId(SETTLEMENT_ID);
        ReflectionTestUtils.setField(fourTimesUnknown, "attemptCount", 4);
        givenRecoveryTargets(fourTimesUnknown);
        when(ledgerClient.settlementTransaction(any())).thenReturn(PaymentLedgerResult.unknown());

        settlementService.recoverSettlements(Instant.now(), 10);

        verify(settlementRepository).recordUnknownAttempt(eq(SETTLEMENT_ID), eq(4), eq(SettlementStatus.MANUAL_REVIEW),
                eq(SettlementReviewReason.RETRY_EXHAUSTED), any());
        verify(settlementItemRepository, never()).deleteAllBySettlementId(any());
    }

    @Test
    void unknownBeforeLimitStaysRequested() {
        Settlement threeTimesUnknown = settlementWithId(SETTLEMENT_ID);
        ReflectionTestUtils.setField(threeTimesUnknown, "attemptCount", 3);
        givenRecoveryTargets(threeTimesUnknown);
        when(ledgerClient.settlementTransaction(any())).thenReturn(PaymentLedgerResult.unknown());

        settlementService.recoverSettlements(Instant.now(), 10);

        verify(settlementRepository).recordUnknownAttempt(eq(SETTLEMENT_ID), eq(3), eq(SettlementStatus.REQUESTED),
                isNull(), any());
    }

    @Test
    void sameKeyForManualReviewSettlementReturnsCurrentState() {
        when(settlementRepository.findByRequestedByAndIdempotencyKey(OWNER_ID, KEY))
                .thenReturn(Optional.of(manualReview(SettlementReviewReason.RETRY_EXHAUSTED, 5)));

        SettlementResult result = settlementService.requestSettlement(OWNER, MERCHANT_ID, KEY);

        assertEquals(SettlementStatus.MANUAL_REVIEW, result.status());
        verify(ledgerClient, never()).settlementTransaction(any());
    }

    @Test
    void resultAppliedByRecoveryFirstIsReturned() {
        Settlement paidByRecovery = settlement(MERCHANT_ID);
        paidByRecovery.paid(LEDGER_TRANSACTION_ID);
        when(settlementRepository.save(any(Settlement.class)))
                .thenAnswer(invocation -> {
                    Settlement settlement = invocation.getArgument(0);
                    if (settlement.getStatus() == SettlementStatus.REQUESTED) {
                        ReflectionTestUtils.setField(settlement, "id", SETTLEMENT_ID);
                        return settlement;
                    }
                    throw new OptimisticLockingFailureException("복구가 먼저 반영");
                });
        when(settlementRepository.findById(SETTLEMENT_ID)).thenReturn(Optional.of(paidByRecovery));

        SettlementResult result = settlementService.requestSettlement(OWNER, MERCHANT_ID, KEY);

        assertEquals(SettlementStatus.PAID, result.status());
        assertEquals(LEDGER_TRANSACTION_ID, result.ledgerTransactionId());
    }

    // 멱등·동시 요청

    @Test
    void sameKeyReturnsExistingSettlementWithoutLedgerCall() {
        Settlement existing = settlement(MERCHANT_ID);
        existing.paid(LEDGER_TRANSACTION_ID);
        when(settlementRepository.findByRequestedByAndIdempotencyKey(OWNER_ID, KEY)).thenReturn(Optional.of(existing));
        when(settlementItemRepository.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                SettlementItem.of(PAYMENT_A, SETTLEMENT_ID, 10_000L), SettlementItem.of(PAYMENT_B, SETTLEMENT_ID, 5_000L)));

        SettlementResult result = settlementService.requestSettlement(OWNER, MERCHANT_ID, KEY);

        assertEquals(SETTLEMENT_ID, result.settlementId());
        assertEquals(SettlementStatus.PAID, result.status());
        assertEquals(2, result.paymentCount());
        verify(ledgerClient, never()).settlementTransaction(any());
        verify(paymentRepository, never()).findSettlementTargets(any(), any(), anyInt());
    }

    @Test
    void sameKeyForFailedSettlementReturnsSameDecline() {
        Settlement existing = settlement(MERCHANT_ID);
        existing.fail("가맹점 잔액이 부족합니다.");
        when(settlementRepository.findByRequestedByAndIdempotencyKey(OWNER_ID, KEY)).thenReturn(Optional.of(existing));

        PaymentDeclinedException exception = assertThrows(PaymentDeclinedException.class,
                () -> settlementService.requestSettlement(OWNER, MERCHANT_ID, KEY));

        assertEquals(PaymentErrorCode.SETTLEMENT_DECLINED, exception.getErrorCode());
        assertEquals("가맹점 잔액이 부족합니다.", exception.getDeclineReason());
    }

    @Test
    void sameKeyForOtherMerchantIsRejected() {
        givenOtherMerchantOwnedByOwner();
        when(settlementRepository.findByRequestedByAndIdempotencyKey(OWNER_ID, KEY))
                .thenReturn(Optional.of(settlement(MERCHANT_ID)));

        assertError(PaymentErrorCode.SETTLEMENT_IDEMPOTENCY_KEY_REUSED,
                () -> settlementService.requestSettlement(OWNER, OTHER_MERCHANT_ID, KEY));
    }

    @Test
    void concurrentRequestWithSameKeyReturnsTheSavedOne() {
        Settlement savedByOther = settlement(MERCHANT_ID);
        when(settlementItemRepository.saveAll(anyList())).thenThrow(new DataIntegrityViolationException("중복 키"));
        when(settlementRepository.findByRequestedByAndIdempotencyKey(OWNER_ID, KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(savedByOther));

        SettlementResult result = settlementService.requestSettlement(OWNER, MERCHANT_ID, KEY);

        assertEquals(SettlementStatus.REQUESTED, result.status());
        verify(ledgerClient, never()).settlementTransaction(any());
    }

    @Test
    void concurrentRequestThatSettledSamePaymentsIsConflict() {
        when(settlementItemRepository.saveAll(anyList())).thenThrow(new DataIntegrityViolationException("결제 중복"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> settlementService.requestSettlement(OWNER, MERCHANT_ID, KEY));

        assertEquals(CommonErrorCode.CONFLICT, exception.getErrorCode());
        verify(ledgerClient, never()).settlementTransaction(any());
    }

    // 결과 모름 복구

    @Test
    void recoveryResendsWithSavedOwnerWalletAndAppliesPaid() {
        Settlement requested = settlement(MERCHANT_ID);
        givenRecoveryTargets(requested);

        settlementService.recoverSettlements(Instant.now(), 10);

        ArgumentCaptor<PaymentLedgerSettlementCommand> command = ArgumentCaptor.forClass(PaymentLedgerSettlementCommand.class);
        verify(ledgerClient).settlementTransaction(command.capture());
        assertEquals(new PaymentLedgerSettlementCommand(SETTLEMENT_ID, OWNER_ID, MERCHANT_ID, OWNER_WALLET_ID,
                CurrencyCode.KRW, 15_000L), command.getValue());
        assertEquals(SettlementStatus.PAID, requested.getStatus());
        // 복구는 주인 지갑을 다시 조회하지 않고 저장된 값으로 보냄
        verify(walletClient, never()).findWallet(any());
    }

    @Test
    void recoveryUsesMerchantLedgerWalletLikeFirstRequest() {
        UUID ledgerWalletId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        givenMerchant(ledgerWalletId);
        givenRecoveryTargets(settlement(MERCHANT_ID));

        settlementService.recoverSettlements(Instant.now(), 10);

        ArgumentCaptor<PaymentLedgerSettlementCommand> command = ArgumentCaptor.forClass(PaymentLedgerSettlementCommand.class);
        verify(ledgerClient).settlementTransaction(command.capture());
        assertEquals(ledgerWalletId, command.getValue().merchantWalletId());
    }

    @Test
    void recoveryAppliesDeclineAndReleasesPayments() {
        Settlement requested = settlement(MERCHANT_ID);
        givenRecoveryTargets(requested);
        when(ledgerClient.settlementTransaction(any())).thenReturn(PaymentLedgerResult.declined("정산이 거절되었습니다."));

        settlementService.recoverSettlements(Instant.now(), 10);

        assertEquals(SettlementStatus.FAILED, requested.getStatus());
        verify(settlementItemRepository).deleteAllBySettlementId(SETTLEMENT_ID);
    }

    @Test
    void recoverySkipsSettlementClaimedByOthers() {
        Settlement requested = settlement(MERCHANT_ID);
        givenRecoveryTargets(requested);
        when(settlementRepository.claimForRecovery(any(), any(), any())).thenReturn(0);

        settlementService.recoverSettlements(Instant.now(), 10);

        verify(ledgerClient, never()).settlementTransaction(any());
    }

    @Test
    void recoveryStopsAfterThreeConsecutiveUnknownResults() {
        List<Settlement> targets = List.of(settlementWithId(UUID.randomUUID()), settlementWithId(UUID.randomUUID()),
                settlementWithId(UUID.randomUUID()), settlementWithId(UUID.randomUUID()));
        givenRecoveryTargets(targets.toArray(Settlement[]::new));
        when(ledgerClient.settlementTransaction(any())).thenReturn(PaymentLedgerResult.unknown());

        settlementService.recoverSettlements(Instant.now(), 10);

        verify(ledgerClient, times(3)).settlementTransaction(any());
    }

    // 조회

    @Test
    void listsSettlementsNewestFirstWithPaymentCounts() {
        Settlement paid = settlement(MERCHANT_ID);
        paid.paid(LEDGER_TRANSACTION_ID);
        when(settlementRepository.findPageByMerchantId(eq(MERCHANT_ID), any()))
                .thenReturn(new PageImpl<>(List.of(paid)));
        when(settlementItemRepository.countBySettlementIds(List.of(SETTLEMENT_ID)))
                .thenReturn(Map.of(SETTLEMENT_ID, 2L));

        var page = settlementService.getSettlements(OWNER, MERCHANT_ID, 0, 500);

        assertEquals(1, page.getContent().size());
        assertEquals(2, page.getContent().get(0).paymentCount());
        ArgumentCaptor<Pageable> pageable =
                ArgumentCaptor.forClass(Pageable.class);
        verify(settlementRepository).findPageByMerchantId(eq(MERCHANT_ID), pageable.capture());
        assertEquals(100, pageable.getValue().getPageSize());
        assertEquals(Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")), pageable.getValue().getSort());
    }

    @Test
    void listRejectsStrangerAndInvalidPage() {
        assertError(PaymentErrorCode.MERCHANT_NOT_FOUND, () -> settlementService.getSettlements(
                new AuthenticatedUser(STRANGER_ID, AuthRole.USER), MERCHANT_ID, 0, 20));
        BusinessException invalid = assertThrows(BusinessException.class,
                () -> settlementService.getSettlements(OWNER, MERCHANT_ID, -1, 20));
        assertEquals(CommonErrorCode.INVALID_REQUEST, invalid.getErrorCode());
    }

    @Test
    void detailShowsIncludedPayments() {
        Settlement paid = settlement(MERCHANT_ID);
        paid.paid(LEDGER_TRANSACTION_ID);
        when(settlementRepository.findById(SETTLEMENT_ID)).thenReturn(Optional.of(paid));
        when(settlementItemRepository.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                SettlementItem.of(PAYMENT_A, SETTLEMENT_ID, 10_000L), SettlementItem.of(PAYMENT_B, SETTLEMENT_ID, 5_000L)));

        var detail = settlementService.getSettlement(MASTER, MERCHANT_ID, SETTLEMENT_ID);

        assertEquals(SettlementStatus.PAID, detail.settlement().status());
        assertEquals(2, detail.settlement().paymentCount());
        assertEquals(PAYMENT_A, detail.items().get(0).paymentId());
        assertEquals(10_000L, detail.items().get(0).amount());
    }

    @Test
    void detailOfOtherMerchantSettlementIsNotFound() {
        when(settlementRepository.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement(OTHER_MERCHANT_ID)));

        assertError(PaymentErrorCode.SETTLEMENT_NOT_FOUND,
                () -> settlementService.getSettlement(OWNER, MERCHANT_ID, SETTLEMENT_ID));
    }

    // 정산 미리보기

    @Test
    void previewSplitsAvailableAndWaitingByRefundPeriod() {
        Duration wait = PaymentService.REFUND_PERIOD.plus(SettlementService.CUTOFF_MARGIN);
        Instant now = Instant.now();
        Instant soonApproved = now.minus(wait).plus(Duration.ofMinutes(10));
        Instant laterApproved = now.minus(wait).plus(Duration.ofMinutes(30));
        when(paymentRepository.findSettlementCandidates(MERCHANT_ID)).thenReturn(List.of(
                new SettlementCandidate(PAYMENT_A, 10_000L, now.minus(wait).minus(Duration.ofMinutes(1))),
                new SettlementCandidate(PAYMENT_B, 5_000L, soonApproved),
                new SettlementCandidate(PAYMENT_C, 3_000L, laterApproved)));

        SettlementPreviewResult preview = settlementService.previewSettlement(OWNER, MERCHANT_ID);

        assertEquals(CurrencyCode.KRW, preview.currency());
        assertEquals(new SettlementPreviewResult.Summary(1, 10_000L), preview.available());
        assertEquals(new SettlementPreviewResult.Summary(1, 10_000L), preview.nextSettlement());
        assertEquals(new SettlementPreviewResult.Summary(2, 8_000L), preview.waiting());
        assertEquals(soonApproved.plus(wait), preview.nextAvailableAt());
        verify(paymentRepository, never()).findSettlementTargets(any(), any(), anyInt());
    }

    @Test
    void previewExcludesCompletedRefundsAndFullyRefundedPayments() {
        Instant old = Instant.now().minus(PaymentService.REFUND_PERIOD).minus(Duration.ofDays(1));
        when(paymentRepository.findSettlementCandidates(MERCHANT_ID)).thenReturn(List.of(
                new SettlementCandidate(PAYMENT_A, 10_000L, old),
                new SettlementCandidate(PAYMENT_B, 5_000L, old)));
        Refund partial = Refund.request(PAYMENT_A, OWNER_ID, "r1", 3_000L, CurrencyCode.KRW, "부분 환불");
        partial.complete(UUID.randomUUID());
        Refund declined = Refund.request(PAYMENT_A, OWNER_ID, "r2", 2_000L, CurrencyCode.KRW, "거절된 환불");
        declined.decline("거절");
        Refund full = Refund.request(PAYMENT_B, OWNER_ID, "r3", 5_000L, CurrencyCode.KRW, "남은 금액 없음");
        full.complete(UUID.randomUUID());
        when(refundRepository.findAllByPaymentIdIn(List.of(PAYMENT_A, PAYMENT_B)))
                .thenReturn(List.of(partial, declined, full));

        SettlementPreviewResult preview = settlementService.previewSettlement(MASTER, MERCHANT_ID);

        assertEquals(new SettlementPreviewResult.Summary(1, 7_000L), preview.available());
        assertEquals(new SettlementPreviewResult.Summary(1, 7_000L), preview.nextSettlement());
        assertEquals(new SettlementPreviewResult.Summary(0, 0L), preview.waiting());
        assertNull(preview.nextAvailableAt());
    }

    @Test
    void previewWithNoCandidatesIsEmptyWithoutRefundLookup() {
        when(paymentRepository.findSettlementCandidates(MERCHANT_ID)).thenReturn(List.of());

        SettlementPreviewResult preview = settlementService.previewSettlement(OWNER, MERCHANT_ID);

        assertEquals(new SettlementPreviewResult.Summary(0, 0L), preview.available());
        assertEquals(new SettlementPreviewResult.Summary(0, 0L), preview.nextSettlement());
        assertEquals(new SettlementPreviewResult.Summary(0, 0L), preview.waiting());
        assertNull(preview.nextAvailableAt());
        verify(refundRepository, never()).findAllByPaymentIdIn(any());
    }

    @Test
    void previewSplitsNextSettlementAtLimitAndLooksUpRefundsInChunks() {
        // 정산 가능 1,500건(각 100원) + 기다리는 결제 1건. 다음 요청에는 앞의 1,000건만 들어감
        Instant old = Instant.now().minus(PaymentService.REFUND_PERIOD).minus(Duration.ofDays(1));
        List<SettlementCandidate> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < 1_500; i++) {
            candidates.add(new SettlementCandidate(UUID.randomUUID(), 100L, old));
        }
        candidates.add(new SettlementCandidate(UUID.randomUUID(), 100L, Instant.now()));
        when(paymentRepository.findSettlementCandidates(MERCHANT_ID)).thenReturn(candidates);

        SettlementPreviewResult preview = settlementService.previewSettlement(OWNER, MERCHANT_ID);

        assertEquals(new SettlementPreviewResult.Summary(1_500, 150_000L), preview.available());
        assertEquals(new SettlementPreviewResult.Summary(1_000, 100_000L), preview.nextSettlement());
        assertEquals(new SettlementPreviewResult.Summary(1, 100L), preview.waiting());
        // 1,501건을 1,000건씩 나눠 환불 조회 → 2번
        verify(refundRepository, times(2)).findAllByPaymentIdIn(any());
    }

    @Test
    void previewRejectsStranger() {
        assertError(PaymentErrorCode.MERCHANT_NOT_FOUND,
                () -> settlementService.previewSettlement(new AuthenticatedUser(STRANGER_ID, AuthRole.USER),
                        MERCHANT_ID));
        verify(paymentRepository, never()).findSettlementCandidates(any());
    }

    private void givenRecoveryTargets(Settlement... targets) {
        when(settlementRepository.findRecoveryTargets(any(), anyInt())).thenReturn(List.of(targets));
        when(settlementRepository.claimForRecovery(any(), any(), any())).thenReturn(1);
        for (Settlement target : targets) {
            when(settlementRepository.findById(target.getId())).thenReturn(Optional.of(target));
        }
    }

    private static Settlement manualReview(SettlementReviewReason reviewReason, int attemptCount) {
        Settlement settlement = settlement(MERCHANT_ID);
        ReflectionTestUtils.setField(settlement, "status", SettlementStatus.MANUAL_REVIEW);
        ReflectionTestUtils.setField(settlement, "reviewReason", reviewReason);
        ReflectionTestUtils.setField(settlement, "attemptCount", attemptCount);
        return settlement;
    }

    private static Settlement settlementWithId(UUID id) {
        Settlement settlement = settlement(MERCHANT_ID);
        ReflectionTestUtils.setField(settlement, "id", id);
        return settlement;
    }

    private void givenMerchant(UUID ledgerWalletId) {
        Merchant merchant = mock(Merchant.class);
        when(merchant.getId()).thenReturn(MERCHANT_ID);
        when(merchant.getCurrency()).thenReturn(CurrencyCode.KRW);
        when(merchant.getLedgerWalletId()).thenReturn(ledgerWalletId);
        when(merchant.getOwnerUserId()).thenReturn(OWNER_ID);
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
    }

    private void givenOtherMerchantOwnedByOwner() {
        Merchant merchant = mock(Merchant.class);
        when(merchant.getId()).thenReturn(OTHER_MERCHANT_ID);
        when(merchant.getOwnerUserId()).thenReturn(OWNER_ID);
        when(merchantRepository.findById(OTHER_MERCHANT_ID)).thenReturn(Optional.of(merchant));
    }

    private static Payment payment(UUID paymentId, long amount) {
        Payment payment = Payment.create(UUID.randomUUID(), UUID.randomUUID(), "pay-" + paymentId, MERCHANT_ID,
                "테스트 가맹점", "주문", CurrencyCode.KRW, amount);
        ReflectionTestUtils.setField(payment, "id", paymentId);
        return payment;
    }

    private static Settlement settlement(UUID merchantId) {
        Settlement settlement = Settlement.request(merchantId, OWNER_ID, KEY, OWNER_ID, OWNER_WALLET_ID,
                CurrencyCode.KRW, 15_000L, Instant.now());
        ReflectionTestUtils.setField(settlement, "id", SETTLEMENT_ID);
        return settlement;
    }

    @SuppressWarnings("unchecked")
    private List<SettlementItem> savedItems() {
        ArgumentCaptor<List<SettlementItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(settlementItemRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private static void assertError(PaymentErrorCode expected, Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertEquals(expected, exception.getErrorCode());
    }
}
