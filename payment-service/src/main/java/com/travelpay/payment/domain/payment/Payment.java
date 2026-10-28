package com.travelpay.payment.domain.payment;

import com.travelpay.common.error.BusinessException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "payments",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID userId;

    private UUID userWalletId;

    private String idempotencyKey;

    private UUID merchantId;

    private String merchantName;

    private String paymentInfo;

    @Enumerated(EnumType.STRING)
    private CurrencyCode currency;

    private Long amount;

    private UUID ledgerId;

    private String declineReason;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Version
    private int version;

    public static Payment create(UUID userId, UUID userWalletId, String idempotencyKey,
                                 UUID merchantId, String merchantName, String paymentInfo,
                                 CurrencyCode currency, Long amount) {
        if (amount == null || amount <= 0) {
            throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT);
        }
        Payment payment = new Payment();
        payment.userId = Objects.requireNonNull(userId);
        payment.userWalletId = userWalletId;
        payment.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        payment.merchantId = Objects.requireNonNull(merchantId);
        payment.merchantName = merchantName;
        payment.paymentInfo = paymentInfo;
        payment.currency = Objects.requireNonNull(currency);
        payment.amount = amount;
        payment.status = PaymentStatus.PENDING;
        return payment;
    }

    public PaymentHistory createdHistory() {
        return PaymentHistory.of(this.id, null, this.status, null, PaymentActor.USER);
    }

    public PaymentHistory approvedHistory(UUID ledgerId) {
        PaymentStatus previous = changeStatus(PaymentStatus.APPROVED);
        this.ledgerId = ledgerId;
        return PaymentHistory.of(this.id, previous, this.status, null, PaymentActor.SYSTEM);
    }

    public PaymentHistory declinedHistory(String reason) {
        PaymentStatus previous = changeStatus(PaymentStatus.DECLINED);
        this.declineReason = reason;
        return PaymentHistory.of(this.id, previous, this.status, reason, PaymentActor.SYSTEM);
    }

    public boolean isOwnedBy(UUID userId) {
        return this.userId.equals(userId);
    }

    public boolean isSameRequest(UUID merchantId, CurrencyCode currency, Long amount) {
        return this.merchantId.equals(merchantId)
                && this.currency == currency
                && this.amount.equals(amount);
    }

    private PaymentStatus changeStatus(PaymentStatus next) {
        if (this.status != PaymentStatus.PENDING) {
            throw new BusinessException(PaymentErrorCode.INVALID_STATUS_TRANSITION);
        }
        PaymentStatus previous = this.status;
        this.status = next;
        return previous;
    }
}
