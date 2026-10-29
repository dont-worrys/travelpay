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

/**
 * 환불 요청 1건의 상세 내역입니다. 결제의 상태 변화는 Payment와 PaymentHistory가 관리합니다.
 */
@Entity
@Table(name = "refunds",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID paymentId;

    private UUID userId;

    private String idempotencyKey;

    private Long amount;

    @Enumerated(EnumType.STRING)
    private CurrencyCode currency;

    private String reason;

    @Enumerated(EnumType.STRING)
    private RefundStatus status;

    private UUID ledgerId;

    private String declineReason;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static Refund request(UUID paymentId, UUID userId, String idempotencyKey,
                                 Long amount, CurrencyCode currency, String reason) {
        if (amount == null || amount <= 0) {
            throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT);
        }
        Refund refund = new Refund();
        refund.paymentId = Objects.requireNonNull(paymentId);
        refund.userId = Objects.requireNonNull(userId);
        refund.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        refund.amount = amount;
        refund.currency = Objects.requireNonNull(currency);
        refund.reason = Objects.requireNonNull(reason);
        refund.status = RefundStatus.REQUESTED;
        return refund;
    }

    public void complete(UUID ledgerId) {
        changeStatus(RefundStatus.COMPLETED);
        this.ledgerId = ledgerId;
    }

    public void decline(String reason) {
        changeStatus(RefundStatus.DECLINED);
        this.declineReason = reason;
    }

    public boolean isSameRequest(UUID paymentId) {
        return this.paymentId.equals(paymentId);
    }

    private void changeStatus(RefundStatus next) {
        if (this.status != RefundStatus.REQUESTED) {
            throw new BusinessException(PaymentErrorCode.INVALID_STATUS_TRANSITION);
        }
        this.status = next;
    }
}
