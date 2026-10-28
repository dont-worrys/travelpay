package com.travelpay.payment.domain.payment;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "merchants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Merchant {
    @Id
    private UUID id;

    private String name;

    @Enumerated(EnumType.STRING)
    private CurrencyCode currency;

    @Enumerated(EnumType.STRING)
    private MerchantStatus status;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

}
