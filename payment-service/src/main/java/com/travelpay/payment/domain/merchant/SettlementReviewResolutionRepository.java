package com.travelpay.payment.domain.merchant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface SettlementReviewResolutionRepository {
    void save(SettlementReviewResolution resolution);

    // 조건은 모두 선택(null이면 거르지 않음)
    Page<SettlementReviewResolution> search(UUID actorId, UUID settlementId, Pageable pageable);
}
