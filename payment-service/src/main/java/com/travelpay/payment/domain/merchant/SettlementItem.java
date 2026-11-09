package com.travelpay.payment.domain.merchant;

import com.travelpay.common.error.BusinessException;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "settlement_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementItem implements Persistable<UUID> {
    @Id
    private UUID paymentId;

    private UUID settlementId;

    // 결제 금액 - 완료된 환불 합계
    private Long amount;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static SettlementItem of(UUID paymentId, UUID settlementId, long amount) {
        if (amount <= 0) {
            throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT);
        }
        SettlementItem item = new SettlementItem();
        item.paymentId = Objects.requireNonNull(paymentId);
        item.settlementId = Objects.requireNonNull(settlementId);
        item.amount = amount;
        return item;
    }

    @Override
    public UUID getId() {
        return paymentId;
    }

    @Override
    public boolean isNew() {
        return createdAt == null;
    }
}
