package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.PaymentReviewResolution;
import com.travelpay.payment.domain.payment.PaymentReviewResolutionRepository;
import com.travelpay.payment.domain.payment.ReviewTarget;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PaymentReviewResolutionRepositoryImpl implements PaymentReviewResolutionRepository {

    private final PaymentReviewResolutionJpaRepository paymentReviewResolutionJpaRepository;

    @Override
    public void save(PaymentReviewResolution resolution) {
        paymentReviewResolutionJpaRepository.save(resolution);
    }

    @Override
    public Page<PaymentReviewResolution> search(ReviewTarget target, UUID actorId, UUID paymentId, Pageable pageable) {
        Specification<PaymentReviewResolution> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (target == ReviewTarget.PAYMENT) {
                predicates.add(cb.isNull(root.get("refundId")));
            }
            if (target == ReviewTarget.REFUND) {
                predicates.add(cb.isNotNull(root.get("refundId")));
            }
            if (actorId != null) {
                predicates.add(cb.equal(root.get("actorId"), actorId));
            }
            if (paymentId != null) {
                predicates.add(cb.equal(root.get("paymentId"), paymentId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return paymentReviewResolutionJpaRepository.findAll(spec, pageable);
    }
}
