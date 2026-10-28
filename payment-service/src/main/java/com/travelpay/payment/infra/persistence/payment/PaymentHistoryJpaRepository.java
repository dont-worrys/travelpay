package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.PaymentHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentHistoryJpaRepository extends JpaRepository<PaymentHistory, UUID> {
}
