package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.PaymentReviewResolution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface PaymentReviewResolutionJpaRepository
        extends JpaRepository<PaymentReviewResolution, UUID>, JpaSpecificationExecutor<PaymentReviewResolution> {
}
