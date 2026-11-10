package com.travelpay.payment.domain.merchant;

import com.travelpay.payment.domain.payment.ReviewLedgerStatus;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** MANUAL_REVIEW 정산을 운영자(MASTER)가 처리한 기록입니다. 상태가 바뀐 처리만 남깁니다. */
@Entity
@Table(name = "settlement_review_resolutions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementReviewResolution {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID settlementId;

    @Enumerated(EnumType.STRING)
    private SettlementReviewReason reviewReason;

    @Enumerated(EnumType.STRING)
    private ReviewLedgerStatus ledgerStatus;

    private UUID ledgerTransactionId;

    @Enumerated(EnumType.STRING)
    private SettlementStatus toStatus;

    private UUID actorId;

    // 시스템이 남기는 처리 설명
    private String description;

    // 운영자가 남긴 메모(선택)
    private String memo;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // DB 제약과 같은 규칙: 원장 기록이 없을 때만 거래 ID가 비어 있음
    public static SettlementReviewResolution of(UUID settlementId, SettlementReviewReason reviewReason,
                                                ReviewLedgerStatus ledgerStatus, UUID ledgerTransactionId,
                                                SettlementStatus toStatus, UUID actorId,
                                                String description, String memo) {
        if ((ledgerStatus == ReviewLedgerStatus.NOT_FOUND) != (ledgerTransactionId == null)) {
            throw new IllegalArgumentException("원장 기록이 있으면 거래 ID가 있어야 합니다. ledgerStatus=" + ledgerStatus);
        }
        if (toStatus != SettlementStatus.PAID && toStatus != SettlementStatus.FAILED) {
            throw new IllegalArgumentException("정산 처리 결과는 PAID·FAILED뿐입니다. toStatus=" + toStatus);
        }
        SettlementReviewResolution resolution = new SettlementReviewResolution();
        resolution.settlementId = Objects.requireNonNull(settlementId);
        resolution.reviewReason = Objects.requireNonNull(reviewReason);
        resolution.ledgerStatus = Objects.requireNonNull(ledgerStatus);
        resolution.ledgerTransactionId = ledgerTransactionId;
        resolution.toStatus = toStatus;
        resolution.actorId = Objects.requireNonNull(actorId);
        resolution.description = Objects.requireNonNull(description);
        resolution.memo = memo;
        return resolution;
    }
}
