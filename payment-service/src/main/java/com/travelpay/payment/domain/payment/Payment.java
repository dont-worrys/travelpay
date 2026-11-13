package com.travelpay.payment.domain.payment;

import com.travelpay.common.error.BusinessException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
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

    private int attemptCount;

    @Enumerated(EnumType.STRING)
    private SettlementReviewReason reviewReason;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

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
        PaymentStatus previous = changeStatus(PaymentStatus.PENDING, PaymentStatus.APPROVED);
        this.ledgerId = ledgerId;
        return PaymentHistory.of(this.id, previous, this.status, null, PaymentActor.SYSTEM);
    }

    public PaymentHistory declinedHistory(String reason) {
        PaymentStatus previous = changeStatus(PaymentStatus.PENDING, PaymentStatus.DECLINED);
        this.declineReason = reason;
        return PaymentHistory.of(this.id, previous, this.status, reason, PaymentActor.SYSTEM);
    }

    // 고객 환불 신청. 승인된 결제만 신청할 수 있고 사유는 필수입니다. 금액은 가맹점이 환불을 실행할 때 정합니다.
    public PaymentHistory refundRequestedByCustomerHistory(String reason) {
        Objects.requireNonNull(reason);
        validateRefundable();
        PaymentStatus previous = changeStatus(PaymentStatus.APPROVED, PaymentStatus.REFUND_REQUESTED);
        return PaymentHistory.of(this.id, previous, this.status, reason, PaymentActor.USER);
    }

    // 고객이 환불 신청을 취소합니다. 가맹점이 환불을 실행한 뒤에는 취소할 수 없습니다.
    public PaymentHistory refundRequestCancelledHistory() {
        requireRefundRequested();
        PaymentStatus previous = changeStatus(PaymentStatus.REFUND_REQUESTED, PaymentStatus.APPROVED);
        return PaymentHistory.of(this.id, previous, this.status, null, PaymentActor.USER);
    }

    // 가맹점 주인 또는 MASTER가 환불 신청을 거절합니다. 결제는 그대로 유효합니다.
    public PaymentHistory refundRequestRejectedHistory(String reason, PaymentActor actor) {
        Objects.requireNonNull(reason);
        requireRefundRequested();
        PaymentStatus previous = changeStatus(PaymentStatus.REFUND_REQUESTED, PaymentStatus.APPROVED);
        return PaymentHistory.of(this.id, previous, this.status, reason, actor);
    }

    // 환불 실행. 가맹점 주인은 고객이 신청한 결제만, MASTER는 승인된 결제도 바로 환불할 수 있습니다.
    public PaymentHistory refundStartedHistory(String reason, PaymentActor actor) {
        validateRefundable();
        if (this.status == PaymentStatus.APPROVED && actor != PaymentActor.MASTER) {
            throw new BusinessException(PaymentErrorCode.REFUND_NOT_REQUESTED);
        }
        PaymentStatus previous = changeStatus(this.status, PaymentStatus.REFUND_PENDING);
        return PaymentHistory.of(this.id, previous, this.status, reason, actor);
    }

    // MANUAL_REVIEW 처리: 원장에 거래가 반영되어 있어 승인으로 확정합니다
    // actor: 운영자 처리는 MASTER, 스케줄러 자동 처리는 SYSTEM
    public PaymentHistory resolveApprovedHistory(UUID ledgerId, PaymentActor actor) {
        PaymentStatus previous = changeStatus(PaymentStatus.MANUAL_REVIEW, PaymentStatus.APPROVED);
        this.ledgerId = Objects.requireNonNull(ledgerId);
        this.reviewReason = null;
        return PaymentHistory.of(this.id, previous, this.status, "원장에 거래가 반영되어 있음", actor);
    }

    // MANUAL_REVIEW 처리: 원장에서 돈이 움직이지 않아 거절로 끝냅니다
    public PaymentHistory resolveDeclinedHistory(String reason, PaymentActor actor) {
        PaymentStatus previous = changeStatus(PaymentStatus.MANUAL_REVIEW, PaymentStatus.DECLINED);
        this.declineReason = reason;
        this.reviewReason = null;
        return PaymentHistory.of(this.id, previous, this.status, reason, actor);
    }

    // 남은 금액이 없으면 REFUNDED, 남아 있으면 부분 환불로 APPROVED로 되돌립니다.
    public PaymentHistory refundCompletedHistory(long refundAmount, long remainingAmount) {
        return refundCompletedHistory(refundAmount, remainingAmount, PaymentActor.SYSTEM);
    }

    public PaymentHistory refundCompletedHistory(long refundAmount, long remainingAmount, PaymentActor actor) {
        if (remainingAmount == 0) {
            PaymentStatus previous = changeStatus(PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED);
            return PaymentHistory.of(this.id, previous, this.status, null, actor);
        }
        PaymentStatus previous = changeStatus(PaymentStatus.REFUND_PENDING, PaymentStatus.APPROVED);
        return PaymentHistory.of(this.id, previous, this.status, "부분 환불 완료: " + refundAmount, actor);
    }

    // 환불이 거절되면 결제는 그대로 유효하므로 APPROVED로 되돌립니다. 거절 사유는 이력과 환불 내역에 남깁니다.
    public PaymentHistory refundDeclinedHistory(String reason) {
        return refundDeclinedHistory(reason, PaymentActor.SYSTEM);
    }

    public PaymentHistory refundDeclinedHistory(String reason, PaymentActor actor) {
        PaymentStatus previous = changeStatus(PaymentStatus.REFUND_PENDING, PaymentStatus.APPROVED);
        return PaymentHistory.of(this.id, previous, this.status, reason, actor);
    }

    public boolean isOwnedBy(UUID userId) {
        return this.userId.equals(userId);
    }

    public boolean isSameRequest(UUID merchantId, CurrencyCode currency, Long amount) {
        return this.merchantId.equals(merchantId)
                && this.currency == currency
                && this.amount.equals(amount);
    }

    public void validateRefundable() {
        if (this.status == PaymentStatus.REFUND_PENDING) {
            throw new BusinessException(PaymentErrorCode.REFUND_IN_PROGRESS);
        }
        if (this.status == PaymentStatus.REFUNDED) {
            throw new BusinessException(PaymentErrorCode.ALREADY_REFUNDED);
        }
        if (this.status != PaymentStatus.APPROVED && this.status != PaymentStatus.REFUND_REQUESTED) {
            throw new BusinessException(PaymentErrorCode.NOT_REFUNDABLE);
        }
    }

    // 취소·거절은 신청 중일 때만. 이미 실행된 환불은 되돌릴 수 없습니다.
    private void requireRefundRequested() {
        if (this.status == PaymentStatus.REFUND_PENDING) {
            throw new BusinessException(PaymentErrorCode.REFUND_IN_PROGRESS);
        }
        if (this.status != PaymentStatus.REFUND_REQUESTED) {
            throw new BusinessException(PaymentErrorCode.REFUND_NOT_REQUESTED);
        }
    }

    private PaymentStatus changeStatus(PaymentStatus expected, PaymentStatus next) {
        if (this.status != expected) {
            throw new BusinessException(PaymentErrorCode.INVALID_STATUS_TRANSITION);
        }
        PaymentStatus previous = this.status;
        this.status = next;
        return previous;
    }
}
