package com.travelpay.payment.infra.persistence.merchant;

import com.travelpay.payment.domain.merchant.SettlementReviewResolution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface SettlementReviewResolutionJpaRepository
        extends JpaRepository<SettlementReviewResolution, UUID>, JpaSpecificationExecutor<SettlementReviewResolution> {
}
