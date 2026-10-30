package com.travelpay.payment.application.payment;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.util.PageableUtils;
import com.travelpay.payment.application.payment.client.ledger.LedgerClient;
import com.travelpay.payment.application.payment.client.ledger.LedgerRefundCommand;
import com.travelpay.payment.application.payment.client.ledger.LedgerResult;
import com.travelpay.payment.application.payment.client.ledger.LedgerTransactionCommand;
import com.travelpay.payment.application.payment.client.wallet.Wallet;
import com.travelpay.payment.application.payment.client.wallet.WalletClient;
import com.travelpay.payment.application.payment.dto.PaymentCommand;
import com.travelpay.payment.application.payment.dto.PaymentListResult;
import com.travelpay.payment.application.payment.dto.PaymentResult;
import com.travelpay.payment.application.payment.dto.RefundCommand;
import com.travelpay.payment.application.payment.dto.RefundResult;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final int MAX_CONSECUTIVE_UNKNOWN = 3;

    private static final String LEDGER_REFUND_REFERENCE_TYPE = "PAYMENT_REFUND";
    private static final int MAX_PAGE_SIZE = 100;
    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final Set<String> LIST_SORT_FIELDS = Set.of("createdAt", "id");

    private final PaymentRepository paymentRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final MerchantRepository merchantRepository;
    private final WalletClient walletClient;
    private final LedgerClient ledgerClient;
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

        if (merchant.getStatus() != MerchantStatus.ACTIVE) {
            throw new BusinessException(PaymentErrorCode.MERCHANT_NOT_ACTIVE);
        }

        if (merchant.getCurrency() != command.currency()) {
            throw new BusinessException(PaymentErrorCode.CURRENCY_MISMATCH);
        }

        // 유저의 지갑 정보, 상태 확인
        Wallet wallet = walletClient.findWallet(command.userId())
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
        Payment settledPayment = settlePayment(savedPayment);

        if (settledPayment.getStatus() == PaymentStatus.DECLINED) {
            throw new BusinessException(PaymentErrorCode.DECLINED);
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

    public PaymentResult getPayment(UUID userId, UUID paymentId) {
        return toResult(findOwnPayment(userId, paymentId));
    }

    public PaymentResult refundRequest(RefundCommand command) {
        // 유저의 결제 정보 확인
        Payment payment = findOwnPayment(command.userId(), command.paymentId());

        // 환불 요청 기록 중복 확인
        Optional<Refund> existing = refundRepository.findByUserIdAndIdempotencyKey(
                command.userId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return replayRefund(existing.get(), payment);
        }

        // 환불 요청 REFUND_PENDING 상태 저장
        RefundProgress started;
        try {
            started = transactionOperations.execute(status -> startRefund(command));
        } catch (OptimisticLockingFailureException e) {
            findOwnPayment(command.userId(), command.paymentId()).validateRefundable();
            throw new BusinessException(CommonErrorCode.CONFLICT);
        } catch (DataIntegrityViolationException e) {
            Refund existingRefund = refundRepository.findByUserIdAndIdempotencyKey(
                            command.userId(), command.idempotencyKey())
                    .orElseThrow(() -> e);
            return replayRefund(existingRefund, findOwnPayment(command.userId(), command.paymentId()));
        }

        // 원장에 환불 요청 전송, 환불 요청 승인·거절 결과 저장, 모르면 REFUND_PENDING 유지
        RefundProgress settled = settleRefund(started.payment(), started.refund());

        if (settled.refund().getStatus() == RefundStatus.DECLINED) {
            throw new BusinessException(PaymentErrorCode.REFUND_DECLINED);
        }
        return toResult(settled.payment());
    }

    // 결제 요청 PENDING 상태 재시도 처리 (배치)
    public void settleStalePayments(Instant before, int limit) {
        int consecutiveUnknown = 0;
        for (Payment payment : paymentRepository.findStalePending(before, limit)) {
            try {
                Payment settled = settlePayment(payment);
                consecutiveUnknown = settled.getStatus() == PaymentStatus.PENDING ? consecutiveUnknown + 1 : 0;
            } catch (RuntimeException e) {
                consecutiveUnknown++;
                log.warn("처리 중 결제 복구 실패 paymentId={}", payment.getId(), e);
            }
            if (consecutiveUnknown >= MAX_CONSECUTIVE_UNKNOWN) {
                log.warn("원장 응답 불가로 결제 복구를 다음 주기로 미룹니다.");
                return;
            }
        }
    }

    // 환불 요청 (refund REQUESTED) 재시도 처리 (배치)
    public void settleStaleRefunds(Instant before, int limit) {
        int consecutiveUnknown = 0;
        for (Refund refund : refundRepository.findStaleRequested(before, limit)) {
            try {
                Payment payment = paymentRepository.findById(refund.getPaymentId())
                        .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
                RefundProgress settled = settleRefund(payment, refund);
                consecutiveUnknown = settled.refund().getStatus() == RefundStatus.REQUESTED ? consecutiveUnknown + 1 : 0;
            } catch (RuntimeException e) {
                consecutiveUnknown++;
                log.warn("처리 중 환불 복구 실패 refundId={}", refund.getId(), e);
            }
            if (consecutiveUnknown >= MAX_CONSECUTIVE_UNKNOWN) {
                log.warn("원장 응답 불가로 환불 복구를 다음 주기로 미룹니다.");
                return;
            }
        }
    }

//    ---------------------------- Helper Method ----------------------------

    // PENDING 결제를 원장 결과로 확정, 결과를 모르면 PENDING인 결제를 그대로 반환
    private Payment settlePayment(Payment payment) {
        LedgerResult ledgerResult = ledgerClient.createTransaction(new LedgerTransactionCommand(
                payment.getUserWalletId(),
                payment.getCurrency(),
                payment.getAmount(),
                "PAYMENT",
                payment.getId(),
                payment.getMerchantId()));

        if (ledgerResult.status() == LedgerResult.Status.UNKNOWN) {
            return payment;
        }

        try {
            return transactionOperations.execute(status -> {
                PaymentHistory history = ledgerResult.status() == LedgerResult.Status.APPROVED
                        ? payment.approvedHistory(ledgerResult.transactionId())
                        : payment.declinedHistory(ledgerResult.declineReason());
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

    // 환불 요청(REQUESTED)을 원장 결과로 확정, 결과를 모르면 결제와 환불을 그대로 반환
    private RefundProgress settleRefund(Payment payment, Refund refund) {
        LedgerResult ledgerResult = ledgerClient.refundTransaction(new LedgerRefundCommand(
                payment.getLedgerId(),
                refund.getCurrency(),
                refund.getAmount(),
                LEDGER_REFUND_REFERENCE_TYPE,
                refund.getId()));

        if (ledgerResult.status() == LedgerResult.Status.UNKNOWN) {
            return new RefundProgress(payment, refund);
        }

        try {
            Payment completedPayment = transactionOperations.execute(
                    status -> completeRefund(payment, refund, ledgerResult));
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

    private PaymentResult replayPayment(Payment existingPayment, PaymentCommand command) {
        if (!existingPayment.isSameRequest(command.merchantId(), command.currency(), command.amount())) {
            throw new BusinessException(PaymentErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        // 결과를 모른 채 남은 결제는 원장에 다시 물어 확정, 원장은 같은 결제 ID로 중복 처리하지 않아야 함
        Payment payment = existingPayment.getStatus() == PaymentStatus.PENDING
                ? settlePayment(existingPayment)
                : existingPayment;
        if (payment.getStatus() == PaymentStatus.DECLINED) {
            throw new BusinessException(PaymentErrorCode.DECLINED);
        }
        return toResult(payment);
    }

    private PaymentResult replayRefund(Refund existing, Payment payment) {
        if (!existing.isSameRequest(payment.getId())) {
            throw new BusinessException(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_REUSED);
        }
        // 결과를 모른 채 남은 환불은 원장에 다시 물어 확정, 원장은 같은 환불 ID로 중복 처리하지 않아야 함
        RefundProgress progress = existing.getStatus() == RefundStatus.REQUESTED
                ? settleRefund(payment, existing)
                : new RefundProgress(payment, existing);
        if (progress.refund().getStatus() == RefundStatus.DECLINED) {
            throw new BusinessException(PaymentErrorCode.REFUND_DECLINED);
        }
        return toResult(progress.payment());
    }

    private RefundProgress startRefund(RefundCommand command) {
        Payment payment = findOwnPayment(command.userId(), command.paymentId());
        PaymentHistory history = payment.refundRequestedHistory(command.reason());
        Payment savedPayment = paymentRepository.save(payment);
        Refund refund = refundRepository.save(Refund.request(
                savedPayment.getId(), command.userId(), command.idempotencyKey(),
                savedPayment.getAmount(), savedPayment.getCurrency(), command.reason()));
        paymentHistoryRepository.save(history);
        return new RefundProgress(savedPayment, refund);
    }

    private Payment completeRefund(Payment payment, Refund refund, LedgerResult ledgerResult) {
        PaymentHistory history;
        if (ledgerResult.status() == LedgerResult.Status.APPROVED) {
            refund.complete(ledgerResult.transactionId());
            history = payment.refundedHistory();
        } else {
            refund.decline(ledgerResult.declineReason());
            history = payment.refundDeclinedHistory(ledgerResult.declineReason());
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

    private record RefundProgress(Payment payment, Refund refund) {
    }
}
