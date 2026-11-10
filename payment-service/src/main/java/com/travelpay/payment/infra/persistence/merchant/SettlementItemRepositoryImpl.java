package com.travelpay.payment.infra.persistence.merchant;

import com.travelpay.payment.domain.merchant.SettlementItem;
import com.travelpay.payment.domain.merchant.SettlementItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class SettlementItemRepositoryImpl implements SettlementItemRepository {

    private final SettlementItemJpaRepository settlementItemJpaRepository;

    @Override
    public List<SettlementItem> saveAll(List<SettlementItem> items) {
        return settlementItemJpaRepository.saveAllAndFlush(items);
    }

    @Override
    public List<SettlementItem> findAllBySettlementId(UUID settlementId) {
        return settlementItemJpaRepository.findAllBySettlementIdOrderByCreatedAtAscPaymentIdAsc(settlementId);
    }

    @Override
    public Map<UUID, Long> countBySettlementIds(Collection<UUID> settlementIds) {
        if (settlementIds.isEmpty()) {
            return Map.of();
        }
        return settlementItemJpaRepository.countBySettlementIds(settlementIds).stream()
                .collect(Collectors.toMap(SettlementItemJpaRepository.SettlementItemCount::getSettlementId,
                        SettlementItemJpaRepository.SettlementItemCount::getPaymentCount));
    }

    @Override
    public void deleteAllBySettlementId(UUID settlementId) {
        settlementItemJpaRepository.deleteAllBySettlementId(settlementId);
    }

    @Override
    public boolean existsByPaymentId(UUID paymentId) {
        return settlementItemJpaRepository.existsById(paymentId);
    }
}
