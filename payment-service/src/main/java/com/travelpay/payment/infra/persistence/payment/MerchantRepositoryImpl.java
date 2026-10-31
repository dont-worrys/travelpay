package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.Merchant;
import com.travelpay.payment.domain.payment.MerchantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MerchantRepositoryImpl implements MerchantRepository {

    private final MerchantJpaRepository merchantJpaRepository;
    @Override
    public Optional<Merchant> findById(UUID merchantId) {
        return merchantJpaRepository.findById(merchantId);
    }
}
