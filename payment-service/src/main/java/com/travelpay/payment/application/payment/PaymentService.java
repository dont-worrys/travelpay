package com.travelpay.payment.application.payment;

import com.travelpay.common.error.BusinessException;
import com.travelpay.payment.application.payment.client.ledger.LedgerClient;
import com.travelpay.payment.application.payment.client.ledger.LedgerResult;
import com.travelpay.payment.application.payment.client.ledger.LedgerTransactionCommand;
import com.travelpay.payment.application.payment.client.wallet.CurrencyWallet;
import com.travelpay.payment.application.payment.client.wallet.WalletClient;
import com.travelpay.payment.application.payment.dto.PaymentCommand;
import com.travelpay.payment.application.payment.dto.PaymentResult;
import com.travelpay.payment.domain.payment.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final MerchantRepository merchantRepository;
    private final WalletClient walletClient;
    private final LedgerClient ledgerClient;

    public PaymentResult paymentRequest(PaymentCommand command) {
        Optional<Payment> existing = paymentRepository.findByUserIdAndIdempotencyKey(
                command.userId(), command.idempotencyKey());

        if (existing.isPresent()) {
            Payment existingPayment = existing.get();

            if (!existingPayment.isSameRequest(command.merchantId(), command.currency(), command.amount())) {
                throw new BusinessException(PaymentErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            if (existingPayment.getStatus() == PaymentStatus.DECLINED) {
                throw new BusinessException(PaymentErrorCode.DECLINED);
            }
            return PaymentResult.from(existingPayment);
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

        // 거래 요청 생성이랑 상태 변경 기록 트랜잭션으로 하나의 동작으로 묶기 (개선 사항)
        Payment savedPayment = paymentRepository.save(payment);
        paymentHistoryRepository.save(savedPayment.createdHistory());

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

        PaymentHistory history = ledgerResult.status() == LedgerResult.Status.APPROVED
                ? savedPayment.approvedHistory(ledgerResult.transactionId())
                : savedPayment.declinedHistory(ledgerResult.declineReason());

        Payment completedPayment = paymentRepository.save(savedPayment);

        paymentHistoryRepository.save(history);

        if (completedPayment.getStatus() == PaymentStatus.DECLINED) {
            throw new BusinessException(PaymentErrorCode.DECLINED);
        }

        return PaymentResult.from(completedPayment);
    }

    public PaymentResult getPayment(UUID userId, UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .filter(found -> found.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        return PaymentResult.from(payment);
    }

}
