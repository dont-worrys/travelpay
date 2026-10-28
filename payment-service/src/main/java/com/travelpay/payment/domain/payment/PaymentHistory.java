package com.travelpay.payment.domain.payment;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "payment_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    private PaymentStatus previousStatus;

    @Enumerated(EnumType.STRING)
    private PaymentStatus currentStatus;

    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentActor createdBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static PaymentHistory of(UUID paymentId, PaymentStatus previousStatus,
                                    PaymentStatus currentStatus, String reason, PaymentActor createdBy) {
        PaymentHistory history = new PaymentHistory();
        history.paymentId = paymentId;
        history.previousStatus = previousStatus;
        history.currentStatus = currentStatus;
        history.reason = reason;
        history.createdBy = Objects.requireNonNull(createdBy);
        return history;
    }
}
