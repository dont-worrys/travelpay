package com.travelpay.payment.application.payment;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.payment.application.payment.ManualReviewRules.PaymentDecision;
import com.travelpay.payment.application.payment.ManualReviewRules.RefundDecision;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerClient;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerLookup;
import com.travelpay.payment.application.payment.dto.ManualReviewPreview;
import com.travelpay.payment.application.payment.dto.ManualReviewResolutionResult;
import com.travelpay.payment.application.payment.dto.ManualReviewResolveResult;
import com.travelpay.payment.application.payment.dto.PaymentManualReviewResult;
import com.travelpay.payment.application.payment.dto.PaymentReviewDetailResult;
import com.travelpay.payment.application.payment.dto.RefundReviewDetailResult;
import com.travelpay.payment.domain.merchant.Merchant;
import com.travelpay.payment.domain.merchant.MerchantRepository;
import com.travelpay.payment.domain.payment.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * MANUAL_REVIEW 결제·환불을 MASTER가 처리합니다. 결과는 사람이 고르지 않고 원장 조회 결과로 정합니다.
 * 원장 조회는 트랜잭션 밖에서 하고, 상태 변경·결제 이력·처리 기록은 한 트랜잭션으로 저장합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReviewService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Sort OLDEST_FIRST = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final PaymentRepository paymentRepository;
    private final MerchantRepository merchantRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final RefundRepository refundRepository;
    private final PaymentReviewResolutionRepository resolutionRepository;
    private final PaymentLedgerClient ledgerClient;
    private final TransactionOperations transactionOperations;

    public Page<PaymentManualReviewResult> getPaymentManualReviews(SettlementReviewReason reviewReason,
                                                                   int page, int size) {
        return paymentRepository.findManualReviews(reviewReason, pageRequest(page, size, OLDEST_FIRST))
                .map(PaymentManualReviewResult::from);
    }

    // 정렬은 쿼리에 고정되어 있어 정렬 없이 넘김
    public Page<RefundManualReview> getRefundManualReviews(SettlementReviewReason reviewReason, int page, int size) {
        return refundRepository.findManualReviews(reviewReason, pageRequest(page, size, Sort.unsorted()));
    }

    // 운영자 처리 기록 목록(최신순). 조건은 모두 선택
    public Page<ManualReviewResolutionResult> getResolutions(ReviewTarget target, UUID actorId, UUID paymentId,
                                                             int page, int size) {
        return resolutionRepository.search(target, actorId, paymentId, pageRequest(page, size, NEWEST_FIRST))
                .map(ManualReviewResolutionResult::from);
    }

    // 처리 전 확인용 단건 조회. 원장 조회에 실패해도 결제 정보는 보여 줌
    public PaymentReviewDetailResult getPaymentReview(UUID actorId, UUID paymentId) {
        Payment payment = findManualReviewPayment(paymentId);
        log.info("관리자 결제 MANUAL_REVIEW 단건 조회 actorId={} paymentId={}", actorId, paymentId);
        PaymentLedgerLookup ledger = lookupOrNull(() -> ledgerClient.findPaymentTransaction(paymentId),
                "paymentId=" + paymentId);
        if (ledger == null) {
            return PaymentReviewDetailResult.of(payment, ManualReviewPreview.lookupFailed());
        }
        PaymentDecision decision = ManualReviewRules.decidePayment(payment.getReviewReason(), ledger,
                expectedPaymentEntries(payment));
        ManualReviewPreview.Expected expected = new ManualReviewPreview.Expected(decision.resolvable(),
                decision.resolvable() ? decision.toStatus().name() : null, decision.toStatus(), decision.description());
        return PaymentReviewDetailResult.of(payment, new ManualReviewPreview(ledger, expected));
    }

    // 처리 전 확인용 단건 조회. 예상 결제 상태는 처리 API와 같은 규칙으로 계산
    public RefundReviewDetailResult getRefundReview(UUID actorId, UUID refundId) {
        Refund refund = findManualReviewRefund(refundId);
        log.info("관리자 환불 MANUAL_REVIEW 단건 조회 actorId={} refundId={}", actorId, refundId);
        Payment payment = paymentRepository.findById(refund.getPaymentId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        long refundedBefore = completedRefundAmount(payment.getId());
        PaymentLedgerLookup ledger = lookupOrNull(() -> ledgerClient.findRefundTransaction(refundId),
                "refundId=" + refundId);
        if (ledger == null) {
            return RefundReviewDetailResult.of(refund, payment, refundedBefore, ManualReviewPreview.lookupFailed());
        }
        RefundDecision decision = ManualReviewRules.decideRefund(refund.getReviewReason(), ledger,
                expectedRefundEntries(refund, payment));
        ManualReviewPreview.Expected expected = decision.resolvable()
                ? new ManualReviewPreview.Expected(true, decision.toStatus().name(),
                        ManualReviewRules.paymentStatusAfterRefund(decision.toStatus(), payment.getAmount(),
                                refundedBefore, refund.getAmount()),
                        decision.description())
                : new ManualReviewPreview.Expected(false, null, null, decision.description());
        return RefundReviewDetailResult.of(refund, payment, refundedBefore, new ManualReviewPreview(ledger, expected));
    }

    public ManualReviewResolveResult resolvePayment(UUID actorId, UUID paymentId, String memo) {
        Payment payment = findManualReviewPayment(paymentId);
        SettlementReviewReason reviewReason = payment.getReviewReason();
        PaymentLedgerLookup ledger = paymentLedgerForResolve(reviewReason, paymentId, actorId);
        PaymentDecision decision = ManualReviewRules.decidePayment(reviewReason, ledger,
                expectedPaymentEntries(payment));
        requireResolvable(decision.resolvable(), reviewReason, ledger, "paymentId=" + paymentId);

        ManualReviewResolveResult result;
        try {
            result = Objects.requireNonNull(transactionOperations.execute(status -> {
                PaymentHistory history = decision.toStatus() == PaymentStatus.APPROVED
                        ? payment.resolveApprovedHistory(ledger.transactionId())
                        : payment.resolveDeclinedHistory(decision.declineReason());
                Payment saved = paymentRepository.save(payment);
                paymentHistoryRepository.save(history);
                resolutionRepository.save(PaymentReviewResolution.ofPayment(paymentId, reviewReason,
                        ledger.status(), ledger.transactionId(), saved.getStatus(), actorId,
                        decision.description(), memo));
                return new ManualReviewResolveResult(paymentId, null, saved.getStatus().name(), saved.getStatus(),
                        ledger.status(), ledger.transactionId());
            }));
        } catch (OptimisticLockingFailureException e) {
            // 원장을 조회하는 사이 다른 처리가 먼저 끝남
            throw new BusinessException(PaymentErrorCode.NOT_MANUAL_REVIEW);
        }
        // 커밋이 끝난 뒤에 남김(커밋 중 충돌로 롤백되면 남지 않음)
        log.info("결제 MANUAL_REVIEW 처리 paymentId={} reviewReason={} ledger={} → {} actorId={}",
                paymentId, reviewReason, ledger.status(), result.toStatus(), actorId);
        return result;
    }

    // 원장 조회. 재시도를 다 쓴 결제가 원장에 없으면, 늦게 커밋될 수 있는 마지막 요청을 막으려고 취소 기록을 먼저 남기고
    // 그 응답(취소 확정 또는 먼저 반영된 거래)으로 처리한다. 취소 여부를 모르면 503(상태 유지)
    private PaymentLedgerLookup paymentLedgerForResolve(SettlementReviewReason reviewReason, UUID paymentId,
                                                        UUID actorId) {
        PaymentLedgerLookup found = ledgerClient.findPaymentTransaction(paymentId);
        if (!ManualReviewRules.requiresLedgerCancel(reviewReason, found)) {
            return found;
        }
        PaymentLedgerLookup afterCancel = ledgerClient.cancelPaymentTransaction(paymentId, actorId);
        log.info("결제 MANUAL_REVIEW 처리 전 원장 취소 paymentId={} result={}", paymentId, afterCancel.status());
        return afterCancel;
    }

    public ManualReviewResolveResult resolveRefund(UUID actorId, UUID refundId, String memo) {
        Refund refund = findManualReviewRefund(refundId);
        SettlementReviewReason reviewReason = refund.getReviewReason();
        PaymentLedgerLookup ledger = ledgerClient.findRefundTransaction(refundId);
        Payment refundedPayment = paymentRepository.findById(refund.getPaymentId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        RefundDecision decision = ManualReviewRules.decideRefund(reviewReason, ledger,
                expectedRefundEntries(refund, refundedPayment));
        requireResolvable(decision.resolvable(), reviewReason, ledger, "refundId=" + refundId);

        RefundStatus toStatus = decision.toStatus();
        ManualReviewResolveResult result;
        try {
            result = Objects.requireNonNull(transactionOperations.execute(status -> {
                Instant now = Instant.now();
                int updated = switch (toStatus) {
                    case COMPLETED -> refundRepository.resolveCompleted(refundId, ledger.transactionId(), now);
                    case DECLINED -> refundRepository.resolveDeclined(refundId, decision.declineReason(), now);
                    case REQUESTED -> refundRepository.resolveRetry(refundId, now);
                    default -> throw new IllegalStateException("환불 처리 결과가 아닙니다. toStatus=" + toStatus);
                };
                if (updated == 0) {
                    // 원장을 조회하는 사이 다른 처리가 먼저 끝남. 예외로 트랜잭션을 되돌림
                    throw new BusinessException(PaymentErrorCode.NOT_MANUAL_REVIEW);
                }

                // 위 업데이트가 영속성 컨텍스트를 비우므로 결제는 업데이트 뒤에 다시 읽음
                Payment payment = paymentRepository.findById(refund.getPaymentId())
                        .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
                if (toStatus == RefundStatus.COMPLETED) {
                    // 이 환불은 이미 COMPLETED로 반영되어 합계에 포함됨
                    long completed = completedRefundAmount(payment.getId());
                    paymentHistoryRepository.save(payment.refundCompletedHistory(
                            refund.getAmount(), payment.getAmount() - completed, PaymentActor.MASTER));
                    payment = paymentRepository.save(payment);
                } else if (toStatus == RefundStatus.DECLINED) {
                    paymentHistoryRepository.save(
                            payment.refundDeclinedHistory(decision.declineReason(), PaymentActor.MASTER));
                    payment = paymentRepository.save(payment);
                }
                // REQUESTED(R3)는 결제를 REFUND_PENDING 그대로 둠

                resolutionRepository.save(PaymentReviewResolution.ofRefund(payment.getId(), refundId, reviewReason,
                        ledger.status(), ledger.transactionId(), toStatus, actorId, decision.description(), memo));
                return new ManualReviewResolveResult(payment.getId(), refundId, toStatus.name(), payment.getStatus(),
                        ledger.status(), ledger.transactionId());
            }));
        } catch (OptimisticLockingFailureException e) {
            throw new BusinessException(PaymentErrorCode.NOT_MANUAL_REVIEW);
        }
        // 커밋이 끝난 뒤에 남김(커밋 중 충돌로 롤백되면 남지 않음)
        log.info("환불 MANUAL_REVIEW 처리 refundId={} reviewReason={} ledger={} → {} payment={} actorId={}",
                refundId, reviewReason, ledger.status(), toStatus, result.paymentStatus(), actorId);
        return result;
    }

    private Payment findManualReviewPayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        if (payment.getStatus() != PaymentStatus.MANUAL_REVIEW) {
            throw new BusinessException(PaymentErrorCode.NOT_MANUAL_REVIEW);
        }
        return payment;
    }

    private Refund findManualReviewRefund(UUID refundId) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.REFUND_NOT_FOUND));
        if (refund.getStatus() != RefundStatus.MANUAL_REVIEW) {
            throw new BusinessException(PaymentErrorCode.NOT_MANUAL_REVIEW);
        }
        return refund;
    }

    // 결제가 원장에 남겼어야 할 항목: 사용자 지갑 → 가맹점 원장 지갑 (결제 요청 때와 같은 구성)
    private List<PaymentLedgerLookup.Entry> expectedPaymentEntries(Payment payment) {
        String currency = payment.getCurrency().name();
        return List.of(
                new PaymentLedgerLookup.Entry(payment.getUserWalletId(), currency, "DEBIT", payment.getAmount(),
                        "PAYMENT_OUT"),
                new PaymentLedgerLookup.Entry(merchantLedgerWalletId(payment.getMerchantId()), currency, "CREDIT",
                        payment.getAmount(), "PAYMENT_IN"));
    }

    // 환불이 원장에 남겼어야 할 항목: 가맹점 원장 지갑 → 사용자 지갑 (환불 요청 때와 같은 구성)
    private List<PaymentLedgerLookup.Entry> expectedRefundEntries(Refund refund, Payment payment) {
        String currency = refund.getCurrency().name();
        return List.of(
                new PaymentLedgerLookup.Entry(merchantLedgerWalletId(payment.getMerchantId()), currency, "DEBIT",
                        refund.getAmount(), "PAYMENT_REFUND_OUT"),
                new PaymentLedgerLookup.Entry(payment.getUserWalletId(), currency, "CREDIT", refund.getAmount(),
                        "PAYMENT_REFUND_IN"));
    }

    // 원장 계정이 있는 가맹점만 결제할 수 있으므로, 없으면 데이터 이상
    private UUID merchantLedgerWalletId(UUID merchantId) {
        return merchantRepository.findById(merchantId)
                .map(Merchant::getLedgerWalletId)
                .orElseThrow(() -> new IllegalStateException("가맹점 원장 지갑을 찾을 수 없습니다. merchantId=" + merchantId));
    }

    private long completedRefundAmount(UUID paymentId) {
        return refundRepository.findAllByPaymentId(paymentId).stream()
                .filter(r -> r.getStatus() == RefundStatus.COMPLETED)
                .mapToLong(Refund::getAmount)
                .sum();
    }

    // 단건 조회는 원장 조회 실패를 오류로 끝내지 않고 "조회 실패"로 보여 줌
    private static PaymentLedgerLookup lookupOrNull(Supplier<PaymentLedgerLookup> lookup, String target) {
        try {
            return lookup.get();
        } catch (BusinessException e) {
            if (e.getErrorCode() != PaymentErrorCode.LEDGER_LOOKUP_UNAVAILABLE) {
                throw e;
            }
            log.warn("단건 조회 중 원장 조회 실패 {}", target);
            return null;
        }
    }

    // 원장 POSTED인데 금액·지갑이 우리 기록과 다름(멈춘 사유와 무관) → 원장 보정 필요
    private static void requireResolvable(boolean resolvable, SettlementReviewReason reviewReason,
                                          PaymentLedgerLookup ledger, String target) {
        if (resolvable) {
            return;
        }
        log.error("원장 보정 필요: 원장 거래 내용이 우리 기록과 다름 {} reviewReason={} ledgerTransactionId={} ledgerEntries={}",
                target, reviewReason, ledger.transactionId(), ledger.entries());
        throw new BusinessException(PaymentErrorCode.LEDGER_CORRECTION_REQUIRED);
    }

    private static Pageable pageRequest(int page, int size, Sort sort) {
        if (page < 0 || size < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), sort);
    }
}
