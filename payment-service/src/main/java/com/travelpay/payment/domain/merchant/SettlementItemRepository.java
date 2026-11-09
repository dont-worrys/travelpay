package com.travelpay.payment.domain.merchant;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface SettlementItemRepository {
    // 이미 정산된 결제가 있으면 PK 충돌
    List<SettlementItem> saveAll(List<SettlementItem> items);
    List<SettlementItem> findAllBySettlementId(UUID settlementId);
    // 정산별 포함 결제 수. 항목이 없는 정산은 키가 없음
    Map<UUID, Long> countBySettlementIds(Collection<UUID> settlementIds);
    // 원장이 정산을 거절했을 때 포함됐던 결제를 다시 대상으로 되돌림
    void deleteAllBySettlementId(UUID settlementId);
    // 정산에 들어간 결제인지
    boolean existsByPaymentId(UUID paymentId);
}
