package com.travelpay.payment.infra.persistence.merchant;

import com.travelpay.payment.domain.merchant.Merchant;
import com.travelpay.payment.domain.merchant.MerchantRepository;
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

    @Override
    public Merchant save(Merchant merchant) {
        return merchantJpaRepository.save(merchant);
    }

    @Override
    public Optional<Merchant> findByIdForUpdate(UUID merchantId) {
        return merchantJpaRepository.findByIdForUpdate(merchantId);
    }
}
