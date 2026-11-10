package com.travelpay.payment.infra.persistence.merchant;

import com.travelpay.payment.domain.merchant.SettlementReviewResolution;
import com.travelpay.payment.domain.merchant.SettlementReviewResolutionRepository;
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
public class SettlementReviewResolutionRepositoryImpl implements SettlementReviewResolutionRepository {

    private final SettlementReviewResolutionJpaRepository settlementReviewResolutionJpaRepository;

    @Override
    public void save(SettlementReviewResolution resolution) {
        settlementReviewResolutionJpaRepository.save(resolution);
    }

    @Override
    public Page<SettlementReviewResolution> search(UUID actorId, UUID settlementId, Pageable pageable) {
        Specification<SettlementReviewResolution> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actorId != null) {
                predicates.add(cb.equal(root.get("actorId"), actorId));
            }
            if (settlementId != null) {
                predicates.add(cb.equal(root.get("settlementId"), settlementId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return settlementReviewResolutionJpaRepository.findAll(spec, pageable);
    }
}
