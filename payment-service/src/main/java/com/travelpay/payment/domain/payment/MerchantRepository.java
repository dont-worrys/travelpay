package com.travelpay.payment.domain.payment;

import java.util.Optional;
import java.util.UUID;

public interface MerchantRepository {
    Optional<Merchant> findById(UUID merchantId);
}
