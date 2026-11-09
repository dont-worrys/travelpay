package com.travelpay.payment.domain.merchant;

import com.travelpay.common.error.BusinessException;
import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
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
@Table(name = "settlements",
        uniqueConstraints = @UniqueConstraint(columnNames = {"requested_by", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID merchantId;

    // 요청자(가맹점 주인 또는 MASTER)
    private UUID requestedBy;

    private String idempotencyKey;

    // 정산을 받는 가맹점 주인과 그 지갑 (요청 시점). 결과 모름 재전송도 이 값으로 보냅니다.
    private UUID ownerUserId;

    private UUID ownerWalletId;

    @Enumerated(EnumType.STRING)
    private CurrencyCode currency;

    private Long amount;

    // (승인 시각 + 환불 기간 + 여유 시간)이 이 시각 전인 결제만 포함
    private Instant cutoffAt;

    @Enumerated(EnumType.STRING)
    private SettlementStatus status;

    private UUID ledgerTransactionId;

    private String failureReason;

    private int attemptCount;

    // MANUAL_REVIEW일 때만 있음
    @Enumerated(EnumType.STRING)
    private SettlementReviewReason reviewReason;

    @Version
    private long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static Settlement request(UUID merchantId, UUID requestedBy, String idempotencyKey,
                                     UUID ownerUserId, UUID ownerWalletId, CurrencyCode currency,
                                     long amount, Instant cutoffAt) {
        if (amount <= 0) {
            throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT);
        }
        Settlement settlement = new Settlement();
        settlement.merchantId = Objects.requireNonNull(merchantId);
        settlement.requestedBy = Objects.requireNonNull(requestedBy);
        settlement.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        settlement.ownerUserId = Objects.requireNonNull(ownerUserId);
        settlement.ownerWalletId = Objects.requireNonNull(ownerWalletId);
        settlement.currency = Objects.requireNonNull(currency);
        settlement.amount = amount;
        settlement.cutoffAt = Objects.requireNonNull(cutoffAt);
        settlement.status = SettlementStatus.REQUESTED;
        return settlement;
    }

    // 원장 반영 완료
    public void paid(UUID ledgerTransactionId) {
        Objects.requireNonNull(ledgerTransactionId);
        changeStatus(SettlementStatus.REQUESTED, SettlementStatus.PAID);
        this.ledgerTransactionId = ledgerTransactionId;
    }

    // 원장 거절. 거절 사유를 남깁니다.
    public void fail(String reason) {
        Objects.requireNonNull(reason);
        changeStatus(SettlementStatus.REQUESTED, SettlementStatus.FAILED);
        this.failureReason = reason;
    }

    // 운영자 처리: 원장 반영 확인. 멈춘 사유는 처리 기록에 남고 여기서는 지웁니다.
    public void resolvePaid(UUID ledgerTransactionId) {
        Objects.requireNonNull(ledgerTransactionId);
        changeStatus(SettlementStatus.MANUAL_REVIEW, SettlementStatus.PAID);
        this.ledgerTransactionId = ledgerTransactionId;
        this.reviewReason = null;
    }

    // 운영자 처리: 원장 거절·기록 없음 확인
    public void resolveFailed(String reason) {
        Objects.requireNonNull(reason);
        changeStatus(SettlementStatus.MANUAL_REVIEW, SettlementStatus.FAILED);
        this.failureReason = reason;
        this.reviewReason = null;
    }

    // 같은 요청자·같은 키의 재요청인지. 본문이 없어 가맹점만 비교합니다.
    public boolean isSameRequest(UUID merchantId) {
        return this.merchantId.equals(merchantId);
    }

    private void changeStatus(SettlementStatus from, SettlementStatus next) {
        if (this.status != from) {
            throw new BusinessException(PaymentErrorCode.INVALID_STATUS_TRANSITION);
        }
        this.status = next;
    }
}
