package com.travelpay.payment.domain.merchant;

import java.util.Optional;
import java.util.UUID;

public interface MerchantRepository {
    Optional<Merchant> findById(UUID merchantId);

    Merchant save(Merchant merchant);

    Optional<Merchant> findByIdForUpdate(UUID merchantId);
}
