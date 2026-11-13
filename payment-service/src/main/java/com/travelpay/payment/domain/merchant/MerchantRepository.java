package com.travelpay.payment.domain.merchant;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantRepository {
    Optional<Merchant> findById(UUID merchantId);

    // 여러 가맹점을 한 번에 조회. 없는 ID는 결과에서 빠짐
    List<Merchant> findAllById(Collection<UUID> merchantIds);

    Merchant save(Merchant merchant);

    Optional<Merchant> findByIdForUpdate(UUID merchantId);
}
