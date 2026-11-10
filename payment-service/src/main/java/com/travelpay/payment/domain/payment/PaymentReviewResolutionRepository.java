package com.travelpay.payment.domain.payment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface PaymentReviewResolutionRepository {
    void save(PaymentReviewResolution resolution);

    // 조건은 모두 선택(null이면 거르지 않음)
    Page<PaymentReviewResolution> search(ReviewTarget target, UUID actorId, UUID paymentId, Pageable pageable);
}
