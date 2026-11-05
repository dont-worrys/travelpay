package com.travelpay.payment.domain.payment;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * MANUAL_REVIEW 결제·환불을 운영자(MASTER)가 처리한 기록입니다. 상태가 바뀐 처리만 남깁니다.
 * refundId가 없으면 결제 처리, 있으면 환불 처리입니다.
 */
@Entity
@Table(name = "payment_review_resolutions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentReviewResolution {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID paymentId;

    private UUID refundId;

    @Enumerated(EnumType.STRING)
    private SettlementReviewReason reviewReason;

    @Enumerated(EnumType.STRING)
    private ReviewLedgerStatus ledgerStatus;

    private UUID ledgerTransactionId;

    // 결제(PaymentStatus)와 환불(RefundStatus)의 상태가 달라 이름으로 저장합니다
    private String toStatus;

    private UUID actorId;

    // 시스템이 남기는 처리 설명
    private String description;

    // 운영자가 남긴 메모(선택)
    private String memo;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static PaymentReviewResolution ofPayment(UUID paymentId, SettlementReviewReason reviewReason,
                                                    ReviewLedgerStatus ledgerStatus, UUID ledgerTransactionId,
                                                    PaymentStatus toStatus, UUID actorId,
                                                    String description, String memo) {
        return create(paymentId, null, reviewReason, ledgerStatus, ledgerTransactionId,
                toStatus.name(), actorId, description, memo);
    }

    public static PaymentReviewResolution ofRefund(UUID paymentId, UUID refundId, SettlementReviewReason reviewReason,
                                                   ReviewLedgerStatus ledgerStatus, UUID ledgerTransactionId,
                                                   RefundStatus toStatus, UUID actorId,
                                                   String description, String memo) {
        return create(paymentId, Objects.requireNonNull(refundId), reviewReason, ledgerStatus, ledgerTransactionId,
                toStatus.name(), actorId, description, memo);
    }

    // DB 제약과 같은 규칙: 원장 기록이 없을 때만 거래 ID가 비어 있음
    private static PaymentReviewResolution create(UUID paymentId, UUID refundId, SettlementReviewReason reviewReason,
                                                  ReviewLedgerStatus ledgerStatus, UUID ledgerTransactionId,
                                                  String toStatus, UUID actorId, String description, String memo) {
        if ((ledgerStatus == ReviewLedgerStatus.NOT_FOUND) != (ledgerTransactionId == null)) {
            throw new IllegalArgumentException("원장 기록이 있으면 거래 ID가 있어야 합니다. ledgerStatus=" + ledgerStatus);
        }
        PaymentReviewResolution resolution = new PaymentReviewResolution();
        resolution.paymentId = Objects.requireNonNull(paymentId);
        resolution.refundId = refundId;
        resolution.reviewReason = Objects.requireNonNull(reviewReason);
        resolution.ledgerStatus = ledgerStatus;
        resolution.ledgerTransactionId = ledgerTransactionId;
        resolution.toStatus = toStatus;
        resolution.actorId = Objects.requireNonNull(actorId);
        resolution.description = Objects.requireNonNull(description);
        resolution.memo = memo;
        return resolution;
    }
}
