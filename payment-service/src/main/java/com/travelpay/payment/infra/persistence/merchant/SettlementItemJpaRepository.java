package com.travelpay.payment.infra.persistence.merchant;

import com.travelpay.payment.domain.merchant.SettlementItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SettlementItemJpaRepository extends JpaRepository<SettlementItem, UUID> {
    List<SettlementItem> findAllBySettlementIdOrderByCreatedAtAscPaymentIdAsc(UUID settlementId);

    @Query("""
            select i.settlementId as settlementId, count(i) as paymentCount
            from SettlementItem i
            where i.settlementId in :settlementIds
            group by i.settlementId
            """)
    List<SettlementItemCount> countBySettlementIds(@Param("settlementIds") Collection<UUID> settlementIds);

    interface SettlementItemCount {
        UUID getSettlementId();
        long getPaymentCount();
    }

    // 한 번의 DELETE로 지움 (ix_settlement_items_settlement_id 사용)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SettlementItem i where i.settlementId = :settlementId")
    void deleteAllBySettlementId(@Param("settlementId") UUID settlementId);
}
