package com.travelpay.payment.application.payment;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.util.PageableUtils;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerClient;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerRefundCommand;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerResult;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerTransactionCommand;
import com.travelpay.payment.application.payment.client.wallet.PaymentWallet;
import com.travelpay.payment.application.payment.client.wallet.PaymentWalletClient;
import com.travelpay.payment.application.payment.dto.MerchantPaymentListResult;
import com.travelpay.payment.application.payment.dto.PaymentCommand;
import com.travelpay.payment.application.payment.dto.PaymentListResult;
import com.travelpay.payment.application.payment.dto.PaymentResult;
import com.travelpay.payment.application.payment.dto.RefundCommand;
import com.travelpay.payment.application.payment.dto.RefundResult;
import com.travelpay.payment.application.payment.dto.StuckSettlementResult;
import com.travelpay.payment.domain.merchant.Merchant;
import com.travelpay.payment.domain.merchant.MerchantRepository;
import com.travelpay.payment.domain.merchant.MerchantStatus;
import com.travelpay.payment.domain.payment.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final int MAX_CONSECUTIVE_UNKNOWN = 3;
    private static final int MAX_UNKNOWN_ATTEMPTS = 5;
    private static final Duration REFUND_PERIOD = Duration.ofDays(3);

    private static final int MAX_PAGE_SIZE = 100;
    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final Set<String> LIST_SORT_FIELDS = Set.of("createdAt", "id");

    private final PaymentRepository paymentRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final MerchantRepository merchantRepository;
    private final PaymentWalletClient walletClient;
    private final PaymentLedgerClient ledgerClient;
    private final RefundRepository refundRepository;
    private final TransactionOperations transactionOperations;

    public PaymentResult paymentRequest(PaymentCommand command) {
        // 같은 멱등키를 가진 해당 유저의 결제 요청 중복 확인
        Optional<Payment> existing = paymentRepository.findByUserIdAndIdempotencyKey(
                command.userId(), command.idempotencyKey());

        if (existing.isPresent()) {
            return replayPayment(existing.get(), command);
        }

        // 가맹점 정보, 상태, 통화 확인
        Merchant merchant = merchantRepository.findById(command.merchantId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.MERCHANT_NOT_FOUND));

        if (merchant.getStatus() != MerchantStatus.ACTIVE || merchant.getLedgerWalletId() == null) {
            throw new BusinessException(PaymentErrorCode.MERCHANT_NOT_ACTIVE);
        }

        if (merchant.getCurrency() != command.currency()) {
            throw new BusinessException(PaymentErrorCode.CURRENCY_MISMATCH);
        }

        // 유저의 지갑 정보, 상태 확인
        PaymentWallet wallet = walletClient.findWallet(command.userId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.WALLET_NOT_FOUND));

        if (!wallet.isActive()) {
            throw new BusinessException(PaymentErrorCode.WALLET_NOT_ACTIVE);
        }

        // 결제 주문 생성
        Payment payment = Payment.create(
                command.userId(),
                wallet.walletId(),
                command.idempotencyKey(),
                merchant.getId(),
                merchant.getName(),
                command.paymentInfo(),
                command.currency(),
                command.amount());

        // 결제 주문 PENDING 상태 저장
        Payment savedPayment;
        try {
            savedPayment = transactionOperations.execute(status -> {
                Payment saved = paymentRepository.save(payment);
                paymentHistoryRepository.save(saved.createdHistory());
                return saved;
            });
        } catch (DataIntegrityViolationException e) {
            Payment existingPayment = paymentRepository.findByUserIdAndIdempotencyKey(
                            command.userId(), command.idempotencyKey())
                    .orElseThrow(() -> e);
            return replayPayment(existingPayment, command);
        }

        // 거래 원장에 요청 전달, 결제 요청 승인·거절 결과 저장, 모르면 PENDING 유지
        Payment settledPayment = settlePayment(Objects.requireNonNull(savedPayment), merchant.getLedgerWalletId());

        if (settledPayment.getStatus() == PaymentStatus.DECLINED) {
            throw new PaymentDeclinedException(PaymentErrorCode.DECLINED, settledPayment.getDeclineReason());
        }

        return PaymentResult.from(settledPayment);
    }

    public Page<PaymentListResult> getPayments(UUID userId, PaymentStatus status, int page, int size) {
        if (page < 0 || size < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        Pageable pageable = PageableUtils.toStablePageable(
                PageRequest.of(page, size, LIST_SORT), LIST_SORT_FIELDS, "id", MAX_PAGE_SIZE);

        return paymentRepository.findPageByUserId(userId, status, pageable)
                .map(PaymentListResult::from);
    }

    // 가맹점 결제 목록 (가맹점 주인 또는 MASTER). 한 페이지 결제들의 완료된 환불 합계를 함께 담는다
    public Page<MerchantPaymentListResult> getMerchantPayments(AuthenticatedUser user, UUID merchantId,
                                                              PaymentStatus status, int page, int size) {
        validateMerchantAccess(user, merchantId);
        if (page < 0 || size < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        Pageable pageable = PageableUtils.toStablePageable(
                PageRequest.of(page, size, LIST_SORT), LIST_SORT_FIELDS, "id", MAX_PAGE_SIZE);

        Page<Payment> payments = paymentRepository.findPageByMerchantId(merchantId, status, pageable);
        Map<UUID, Long> refunded = refundRepository.findAllByPaymentIdIn(
                        payments.map(Payment::getId).getContent()).stream()
                .filter(refund -> refund.getStatus() == RefundStatus.COMPLETED)
                .collect(Collectors.groupingBy(Refund::getPaymentId, Collectors.summingLong(Refund::getAmount)));

        return payments.map(payment ->
                MerchantPaymentListResult.from(payment, refunded.getOrDefault(payment.getId(), 0L)));
    }

    // 가맹점 결제 단건 (가맹점 주인 또는 MASTER). 다른 가맹점의 결제면 404
    public PaymentResult getMerchantPayment(AuthenticatedUser user, UUID merchantId, UUID paymentId) {
        validateMerchantAccess(user, merchantId);
        Payment payment = paymentRepository.findById(paymentId)
                .filter(found -> found.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        return toResult(payment);
    }

    public PaymentResult getPayment(UUID userId, UUID paymentId) {
        return toResult(findOwnPayment(userId, paymentId));
    }

    public PaymentResult refundRequest(RefundCommand command) {
        // 환불할 결제 확인 (가맹점 주인 또는 MASTER)
        RefundTarget target = findRefundTarget(command);
        Payment payment = target.payment();

        // 환불 요청 기록 중복 확인
        Optional<Refund> existing = refundRepository.findByUserIdAndIdempotencyKey(
                command.userId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return replayRefund(existing.get(), payment, command.amount());
        }

        // 결제 승인 후 환불 기간이 지났으면 환불 불가
        payment.validateRefundable();
        Instant approvedAt = paymentHistoryRepository.findApprovedAt(payment.getId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.NOT_REFUNDABLE));
        if (Instant.now().isAfter(approvedAt.plus(REFUND_PERIOD))) {
            throw new BusinessException(PaymentErrorCode.REFUND_PERIOD_EXPIRED);
        }

        // 환불 요청 REFUND_PENDING 상태 저장
        RefundProgress started;
        try {
            started = transactionOperations.execute(status -> startRefund(command));
        } catch (OptimisticLockingFailureException e) {
            reloadPayment(command.paymentId()).validateRefundable();
            throw new BusinessException(CommonErrorCode.CONFLICT);
        } catch (DataIntegrityViolationException e) {
            Refund existingRefund = refundRepository.findByUserIdAndIdempotencyKey(
                            command.userId(), command.idempotencyKey())
                    .orElseThrow(() -> e);
            return replayRefund(existingRefund, reloadPayment(command.paymentId()), command.amount());
        }

        // 원장에 환불 요청 전송, 환불 요청 승인·거절 결과 저장, 모르면 REFUND_PENDING 유지
        RefundProgress settled = settleRefund(Objects.requireNonNull(started).payment(), started.refund(),
                target.merchantWalletId());

        if (settled.refund().getStatus() == RefundStatus.DECLINED) {
            throw new PaymentDeclinedException(PaymentErrorCode.REFUND_DECLINED, settled.refund().getDeclineReason());
        }
        return toResult(settled.payment());
    }

    // 재시도 대기 시간이 지난 PENDING 결제를 선점해 원장에 다시 요청 (배치)
    public void recoverPayments(Instant now, int limit) {
        int consecutiveUnknown = 0;
        for (Payment target : paymentRepository.findRecoveryTargets(now, limit)) {
            boolean unknown;
            try {
                if (paymentRepository.claimForRecovery(target.getId(), target.getUpdatedAt(), Instant.now()) == 0) {
                    // 다른 인스턴스가 먼저 선점했거나 그 사이 결과가 반영됨
                    continue;
                }
                unknown = isResultUnknown(settlePayment(target, findMerchantWalletId(target.getMerchantId())));
            } catch (RuntimeException e) {
                log.warn("처리 중 결제 복구 실패 paymentId={}", target.getId(), e);
                unknown = true;
            }
            consecutiveUnknown = unknown ? consecutiveUnknown + 1 : 0;
            if (consecutiveUnknown >= MAX_CONSECUTIVE_UNKNOWN) {
                log.warn("원장 응답 불가로 결제 복구를 다음 주기로 미룹니다.");
                return;
            }
        }
    }

    // 재시도 대기 시간이 지난 REQUESTED 환불을 선점해 원장에 다시 요청 (배치)
    public void recoverRefunds(Instant now, int limit) {
        int consecutiveUnknown = 0;
        for (Refund target : refundRepository.findRecoveryTargets(now, limit)) {
            boolean unknown;
            try {
                if (refundRepository.claimForRecovery(target.getId(), target.getUpdatedAt(), Instant.now()) == 0) {
                    // 다른 인스턴스가 먼저 선점했거나 그 사이 결과가 반영됨
                    continue;
                }
                Payment payment = paymentRepository.findById(target.getPaymentId())
                        .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
                unknown = isResultUnknown(
                        settleRefund(payment, target, findMerchantWalletId(payment.getMerchantId())).refund());
            } catch (RuntimeException e) {
                log.warn("처리 중 환불 복구 실패 refundId={}", target.getId(), e);
                unknown = true;
            }
            consecutiveUnknown = unknown ? consecutiveUnknown + 1 : 0;
            if (consecutiveUnknown >= MAX_CONSECUTIVE_UNKNOWN) {
                log.warn("원장 응답 불가로 환불 복구를 다음 주기로 미룹니다.");
                return;
            }
        }
    }

    public long countManualReviewPayments() {
        return paymentRepository.countByStatus(PaymentStatus.MANUAL_REVIEW);
    }

    public long countManualReviewRefunds() {
        return refundRepository.countByStatus(RefundStatus.MANUAL_REVIEW);
    }

    public StuckSettlementResult findStuckSettlements(Instant createdBefore, int sampleSize) {
        return new StuckSettlementResult(
                paymentRepository.countPendingCreatedBefore(createdBefore),
                paymentRepository.findPendingIdsCreatedBefore(createdBefore, sampleSize),
                refundRepository.countRequestedCreatedBefore(createdBefore),
                refundRepository.findRequestedIdsCreatedBefore(createdBefore, sampleSize));
    }

//    ---------------------------- Helper Method ----------------------------

    // 아직 PENDING이거나 한도로 멈춘 건은 결과 모름. 원장이 받아들이지 않은 건은 원장이 응답했으므로 결과 모름이 아님
    private static boolean isResultUnknown(Payment payment) {
        return payment.getStatus() == PaymentStatus.PENDING
                || payment.getReviewReason() == SettlementReviewReason.RETRY_EXHAUSTED;
    }

    private static boolean isResultUnknown(Refund refund) {
        return refund.getStatus() == RefundStatus.REQUESTED
                || refund.getReviewReason() == SettlementReviewReason.RETRY_EXHAUSTED;
    }

    // PENDING 결제를 원장 결과로 확정. 결과를 모르면 시도 횟수를 기록하고(한도면 MANUAL_REVIEW), 받아들이지 않으면 MANUAL_REVIEW
    private Payment settlePayment(Payment payment, UUID merchantWalletId) {
        PaymentLedgerResult paymentLedgerResult = ledgerClient.createTransaction(new PaymentLedgerTransactionCommand(
                payment.getId(),
                payment.getUserId(),
                payment.getUserWalletId(),
                merchantWalletId,
                payment.getCurrency(),
                payment.getAmount()));

        switch (paymentLedgerResult.status()) {
            case UNKNOWN -> {
                return recordPaymentUnknown(payment);
            }
            case NOT_ACCEPTED -> {
                return markPaymentManualReview(payment);
            }
            default -> {
            }
        }

        try {
            return transactionOperations.execute(status -> {
                PaymentHistory history = paymentLedgerResult.status() == PaymentLedgerResult.Status.APPROVED
                        ? payment.approvedHistory(paymentLedgerResult.transactionId())
                        : payment.declinedHistory(paymentLedgerResult.declineReason());
                Payment completed = paymentRepository.save(payment);
                paymentHistoryRepository.save(history);
                return completed;
            });
        } catch (OptimisticLockingFailureException e) {
            // 같은 결제를 다른 요청이 먼저 확정, 확정된 상태를 다시 읽어 반환
            return paymentRepository.findById(payment.getId())
                    .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        }
    }

    // 결과 모름 기록. 5회째면 MANUAL_REVIEW + 이력. 기록 중 오류가 나면 받은 결제를 그대로 반환하고, 스케줄러가 다음에 다시 시도
    private Payment recordPaymentUnknown(Payment payment) {
        boolean exhausted = payment.getAttemptCount() + 1 >= MAX_UNKNOWN_ATTEMPTS;
        try {
            transactionOperations.executeWithoutResult(status -> {
                int updated = paymentRepository.recordUnknownAttempt(payment.getId(), payment.getAttemptCount(),
                        exhausted ? PaymentStatus.MANUAL_REVIEW : PaymentStatus.PENDING,
                        exhausted ? SettlementReviewReason.RETRY_EXHAUSTED : null, Instant.now());
                if (updated == 1 && exhausted) {
                    paymentHistoryRepository.save(manualReviewHistory(payment, SettlementReviewReason.RETRY_EXHAUSTED));
                    log.error("원장 결과를 {}번 확인하지 못해 자동 처리를 멈춤 paymentId={}",
                            MAX_UNKNOWN_ATTEMPTS, payment.getId());
                }
            });
            return paymentRepository.findById(payment.getId()).orElse(payment);
        } catch (RuntimeException e) {
            log.error("결제 결과 모름을 기록하지 못함 paymentId={}", payment.getId(), e);
            return payment;
        }
    }

    // 원장이 요청을 받아들이지 않음. 재요청해도 같은 결과라 MANUAL_REVIEW + 이력
    private Payment markPaymentManualReview(Payment payment) {
        try {
            transactionOperations.executeWithoutResult(status -> {
                if (paymentRepository.markManualReview(payment.getId(),
                        SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, Instant.now()) == 1) {
                    paymentHistoryRepository.save(
                            manualReviewHistory(payment, SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED));
                    log.error("원장이 결제 요청을 받아들이지 않아 자동 처리를 멈춤 paymentId={}", payment.getId());
                }
            });
            return paymentRepository.findById(payment.getId()).orElse(payment);
        } catch (RuntimeException e) {
            log.error("결제 MANUAL_REVIEW를 기록하지 못함 paymentId={}", payment.getId(), e);
            return payment;
        }
    }

    private static PaymentHistory manualReviewHistory(Payment payment, SettlementReviewReason reviewReason) {
        return PaymentHistory.of(payment.getId(), PaymentStatus.PENDING, PaymentStatus.MANUAL_REVIEW,
                reviewReason.name(), PaymentActor.SYSTEM);
    }

    // 환불 요청(REQUESTED)을 원장 결과로 확정.
    // 환불이 MANUAL_REVIEW가 되어도 결제는 REFUND_PENDING 유지
    private RefundProgress settleRefund(Payment payment, Refund refund, UUID merchantWalletId) {
        PaymentLedgerResult paymentLedgerResult = ledgerClient.refundTransaction(new PaymentLedgerRefundCommand(
                refund.getId(),
                payment.getLedgerId(),
                payment.getUserId(),
                payment.getUserWalletId(),
                merchantWalletId,
                refund.getCurrency(),
                refund.getAmount()));

        switch (paymentLedgerResult.status()) {
            case UNKNOWN -> {
                return new RefundProgress(payment, recordRefundUnknown(refund));
            }
            case NOT_ACCEPTED -> {
                return new RefundProgress(payment, markRefundManualReview(refund));
            }
            default -> {
            }
        }

        try {
            Payment completedPayment = transactionOperations.execute(
                    status -> completeRefund(payment, refund, paymentLedgerResult));
            return new RefundProgress(completedPayment, refund);
        } catch (OptimisticLockingFailureException e) {
            // 같은 환불을 다른 요청이 먼저 확정, 확정된 결제와 환불을 다시 읽어 반환
            Payment latestPayment = paymentRepository.findById(payment.getId())
                    .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
            Refund latestRefund = refundRepository.findByUserIdAndIdempotencyKey(
                            refund.getUserId(), refund.getIdempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("확정된 환불을 다시 찾을 수 없습니다."));
            return new RefundProgress(latestPayment, latestRefund);
        }
    }

    // 결과 모름 기록. 5회째면 MANUAL_REVIEW. 기록 중 오류가 나면 받은 환불을 그대로 반환하고, 스케줄러가 다음에 다시 시도
    private Refund recordRefundUnknown(Refund refund) {
        boolean exhausted = refund.getAttemptCount() + 1 >= MAX_UNKNOWN_ATTEMPTS;
        try {
            int updated = refundRepository.recordUnknownAttempt(refund.getId(), refund.getAttemptCount(),
                    exhausted ? RefundStatus.MANUAL_REVIEW : RefundStatus.REQUESTED,
                    exhausted ? SettlementReviewReason.RETRY_EXHAUSTED : null, Instant.now());
            if (updated == 1 && exhausted) {
                log.error("원장 결과를 {}번 확인하지 못해 환불 자동 처리를 멈춤 refundId={}",
                        MAX_UNKNOWN_ATTEMPTS, refund.getId());
            }
            return reloadRefund(refund);
        } catch (RuntimeException e) {
            log.error("환불 결과 모름을 기록하지 못함 refundId={}", refund.getId(), e);
            return refund;
        }
    }

    // 원장이 환불 요청을 받아들이지 않음. 재요청해도 같은 결과라 MANUAL_REVIEW
    private Refund markRefundManualReview(Refund refund) {
        try {
            if (refundRepository.markManualReview(refund.getId(),
                    SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, Instant.now()) == 1) {
                log.error("원장이 환불 요청을 받아들이지 않아 자동 처리를 멈춤 refundId={}", refund.getId());
            }
            return reloadRefund(refund);
        } catch (RuntimeException e) {
            log.error("환불 MANUAL_REVIEW를 기록하지 못함 refundId={}", refund.getId(), e);
            return refund;
        }
    }

    private Refund reloadRefund(Refund refund) {
        return refundRepository.findByUserIdAndIdempotencyKey(refund.getUserId(), refund.getIdempotencyKey())
                .orElse(refund);
    }

    // 결제 시 ledgerWalletId가 있는 가맹점만 통과하므로, 없으면 데이터 이상
    private UUID findMerchantWalletId(UUID merchantId) {
        return merchantRepository.findById(merchantId)
                .map(Merchant::getLedgerWalletId)
                .orElseThrow(() -> new IllegalStateException("가맹점 원장 지갑을 찾을 수 없습니다. merchantId=" + merchantId));
    }

    private PaymentResult replayPayment(Payment existingPayment, PaymentCommand command) {
        if (!existingPayment.isSameRequest(command.merchantId(), command.currency(), command.amount())) {
            throw new BusinessException(PaymentErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        // 결과를 모르는 결제는 원장에 다시 묻지 않고 현재 상태로 응답한다. 재시도는 복구 스케줄러만 맡아 간격·한도를 지킨다
        if (existingPayment.getStatus() == PaymentStatus.DECLINED) {
            throw new PaymentDeclinedException(PaymentErrorCode.DECLINED, existingPayment.getDeclineReason());
        }
        return toResult(existingPayment);
    }

    private PaymentResult replayRefund(Refund existing, Payment payment, Long amount) {
        if (!existing.isSameRequest(payment.getId(), amount)) {
            throw new BusinessException(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_REUSED);
        }
        // 결과를 모르는 환불은 원장에 다시 묻지 않고 현재 상태로 응답한다. 재시도는 복구 스케줄러만 맡아 간격·한도를 지킨다
        if (existing.getStatus() == RefundStatus.DECLINED) {
            throw new PaymentDeclinedException(PaymentErrorCode.REFUND_DECLINED, existing.getDeclineReason());
        }
        return toResult(payment);
    }

    private RefundProgress startRefund(RefundCommand command) {
        Payment payment = reloadPayment(command.paymentId());
        PaymentActor actor = command.role() == AuthRole.MASTER ? PaymentActor.MASTER : PaymentActor.USER;
        PaymentHistory history = payment.refundRequestedHistory(command.reason(), actor);

        // 남은 금액 = 결제 금액 - (완료된 환불 + 결과를 모르는 환불(REQUESTED·MANUAL_REVIEW)), 트랜잭션 안에서 다시 읽은 결제 기준
        long refunded = refundRepository.findAllByPaymentId(payment.getId()).stream()
                .filter(r -> r.getStatus() == RefundStatus.COMPLETED || r.getStatus() == RefundStatus.REQUESTED
                        || r.getStatus() == RefundStatus.MANUAL_REVIEW)
                .mapToLong(Refund::getAmount)
                .sum();
        if (command.amount() > payment.getAmount() - refunded) {
            throw new BusinessException(PaymentErrorCode.REFUND_AMOUNT_EXCEEDED);
        }

        Payment savedPayment = paymentRepository.save(payment);
        Refund refund = refundRepository.save(Refund.request(
                savedPayment.getId(), command.userId(), command.idempotencyKey(),
                command.amount(), savedPayment.getCurrency(), command.reason()));
        paymentHistoryRepository.save(history);
        return new RefundProgress(savedPayment, refund);
    }

    private Payment completeRefund(Payment payment, Refund refund, PaymentLedgerResult paymentLedgerResult) {
        PaymentHistory history;
        if (paymentLedgerResult.status() == PaymentLedgerResult.Status.APPROVED) {
            refund.complete(paymentLedgerResult.transactionId());
            long completed = refundRepository.findAllByPaymentId(payment.getId()).stream()
                    .filter(r -> r.getStatus() == RefundStatus.COMPLETED && !r.getId().equals(refund.getId()))
                    .mapToLong(Refund::getAmount)
                    .sum() + refund.getAmount();
            history = payment.refundCompletedHistory(refund.getAmount(), payment.getAmount() - completed);
        } else {
            refund.decline(paymentLedgerResult.declineReason());
            history = payment.refundDeclinedHistory(paymentLedgerResult.declineReason());
        }
        Payment savedPayment = paymentRepository.save(payment);
        refundRepository.save(refund);
        paymentHistoryRepository.save(history);
        return savedPayment;
    }

    private PaymentResult toResult(Payment payment) {
        List<RefundResult> refunds = refundRepository.findAllByPaymentId(payment.getId()).stream()
                .map(RefundResult::from)
                .toList();
        return PaymentResult.from(payment, refunds);
    }

    private Payment findOwnPayment(UUID userId, UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .filter(found -> found.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    // 가맹점이 없거나 주인·MASTER가 아니면 404 (가맹점 잔액 조회와 같은 규칙)
    private void validateMerchantAccess(AuthenticatedUser user, UUID merchantId) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.MERCHANT_NOT_FOUND));
        if (user.role() != AuthRole.MASTER && !merchant.getOwnerUserId().equals(user.userId())) {
            throw new BusinessException(PaymentErrorCode.MERCHANT_NOT_FOUND);
        }
    }

    // 환불은 결제한 가맹점의 주인 또는 MASTER만 가능, 아니면 결제가 없는 것처럼 404
    private RefundTarget findRefundTarget(RefundCommand command) {
        Payment payment = reloadPayment(command.paymentId());
        Optional<Merchant> merchant = merchantRepository.findById(payment.getMerchantId());
        if (command.role() != AuthRole.MASTER
                && !merchant.map(found -> found.getOwnerUserId().equals(command.userId())).orElse(false)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND);
        }
        // 결제 시 ledgerWalletId가 있는 가맹점만 통과하므로, 없으면 데이터 이상
        UUID merchantWalletId = merchant.map(Merchant::getLedgerWalletId)
                .orElseThrow(() -> new IllegalStateException(
                        "가맹점 원장 지갑을 찾을 수 없습니다. merchantId=" + payment.getMerchantId()));
        return new RefundTarget(payment, merchantWalletId);
    }

    private Payment reloadPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    private record RefundTarget(Payment payment, UUID merchantWalletId) {
    }

    private record RefundProgress(Payment payment, Refund refund) {
    }
}
