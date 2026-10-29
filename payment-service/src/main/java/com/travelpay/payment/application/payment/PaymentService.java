package com.travelpay.payment.application.payment;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.util.PageableUtils;
import com.travelpay.payment.application.payment.client.ledger.LedgerClient;
import com.travelpay.payment.application.payment.client.ledger.LedgerRefundCommand;
import com.travelpay.payment.application.payment.client.ledger.LedgerResult;
import com.travelpay.payment.application.payment.client.ledger.LedgerTransactionCommand;
import com.travelpay.payment.application.payment.client.wallet.CurrencyWallet;
import com.travelpay.payment.application.payment.client.wallet.WalletClient;
import com.travelpay.payment.application.payment.dto.PaymentCommand;
import com.travelpay.payment.application.payment.dto.PaymentListResult;
import com.travelpay.payment.application.payment.dto.PaymentResult;
import com.travelpay.payment.application.payment.dto.RefundCommand;
import com.travelpay.payment.application.payment.dto.RefundResult;
import com.travelpay.payment.domain.payment.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

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
        Optional<Payment> existing = paymentRepository.findByUserIdAndIdempotencyKey(
                command.userId(), command.idempotencyKey());

        if (existing.isPresent()) {
            return replayPayment(existing.get(), command);
        }

        Merchant merchant = merchantRepository.findById(command.merchantId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.MERCHANT_NOT_FOUND));

        if (merchant.getStatus() != MerchantStatus.ACTIVE) {
            throw new BusinessException(PaymentErrorCode.MERCHANT_NOT_ACTIVE);
        }

        if (merchant.getCurrency() != command.currency()) {
            throw new BusinessException(PaymentErrorCode.CURRENCY_MISMATCH);
        }

        CurrencyWallet wallet = walletClient.findCurrencyWallet(command.userId(), command.currency())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.WALLET_NOT_FOUND));

        Payment payment = Payment.create(
                command.userId(),
                wallet.walletCurrencyId(),
                command.idempotencyKey(),
                merchant.getId(),
                merchant.getName(),
                command.paymentInfo(),
                command.currency(),
                command.amount());

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

        LedgerResult ledgerResult = ledgerClient.createTransaction(new LedgerTransactionCommand(
                wallet.ledgerAccountId(),
                savedPayment.getCurrency(),
                savedPayment.getAmount(),
                "PAYMENT",
                savedPayment.getId(),
                savedPayment.getMerchantId()));

        if (ledgerResult.status() == LedgerResult.Status.UNKNOWN) {
            return PaymentResult.from(savedPayment);
        }

        Payment completedPayment = transactionOperations.execute(status -> {
            PaymentHistory history = ledgerResult.status() == LedgerResult.Status.APPROVED
                    ? savedPayment.approvedHistory(ledgerResult.transactionId())
                    : savedPayment.declinedHistory(ledgerResult.declineReason());
            Payment completed = paymentRepository.save(savedPayment);
            paymentHistoryRepository.save(history);
            return completed;
        });

        if (completedPayment.getStatus() == PaymentStatus.DECLINED) {
            throw new BusinessException(PaymentErrorCode.DECLINED);
        }

        return PaymentResult.from(completedPayment);
    }

    private PaymentResult replayPayment(Payment existingPayment, PaymentCommand command) {
        if (!existingPayment.isSameRequest(command.merchantId(), command.currency(), command.amount())) {
            throw new BusinessException(PaymentErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (existingPayment.getStatus() == PaymentStatus.DECLINED) {
            throw new BusinessException(PaymentErrorCode.DECLINED);
        }
        return toResult(existingPayment);
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
        Payment payment = findOwnPayment(command.userId(), command.paymentId());

        Optional<Refund> existing = refundRepository.findByUserIdAndIdempotencyKey(
                command.userId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return replayRefund(existing.get(), payment);
        }

        RefundStarted started;
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

        LedgerResult ledgerResult = ledgerClient.refundTransaction(new LedgerRefundCommand(
                started.payment().getLedgerId(),
                started.refund().getCurrency(),
                started.refund().getAmount(),
                LEDGER_REFUND_REFERENCE_TYPE,
                started.refund().getId()));

        if (ledgerResult.status() == LedgerResult.Status.UNKNOWN) {
            return toResult(started.payment());
        }

        Payment completedPayment = transactionOperations.execute(
                status -> completeRefund(started.payment(), started.refund(), ledgerResult));

        if (ledgerResult.status() == LedgerResult.Status.DECLINED) {
            throw new BusinessException(PaymentErrorCode.REFUND_DECLINED);
        }
        return toResult(completedPayment);
    }

    private PaymentResult replayRefund(Refund existing, Payment payment) {
        if (!existing.isSameRequest(payment.getId())) {
            throw new BusinessException(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_REUSED);
        }
        if (existing.getStatus() == RefundStatus.DECLINED) {
            throw new BusinessException(PaymentErrorCode.REFUND_DECLINED);
        }

        return toResult(payment);
    }

    private RefundStarted startRefund(RefundCommand command) {
        Payment payment = findOwnPayment(command.userId(), command.paymentId());
        PaymentHistory history = payment.refundRequestedHistory(command.reason());
        Payment savedPayment = paymentRepository.save(payment);
        Refund refund = refundRepository.save(Refund.request(
                savedPayment.getId(), command.userId(), command.idempotencyKey(),
                savedPayment.getAmount(), savedPayment.getCurrency(), command.reason()));
        paymentHistoryRepository.save(history);
        return new RefundStarted(savedPayment, refund);
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

    private record RefundStarted(Payment payment, Refund refund) {
    }
}
