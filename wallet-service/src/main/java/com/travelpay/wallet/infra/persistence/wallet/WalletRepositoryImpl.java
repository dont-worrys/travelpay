package com.travelpay.wallet.infra.persistence.wallet;

import com.travelpay.wallet.domain.wallet.Wallet;
import com.travelpay.wallet.domain.wallet.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class WalletRepositoryImpl implements WalletRepository {

    private final WalletJpaRepository jpaRepository;

    @Override
    public void addIfAbsent(UUID userId) {
        jpaRepository.insertIfAbsent(UUID.randomUUID(), userId);
    }

    @Override
    public Optional<Wallet> findByUserId(UUID userId) {
        return jpaRepository.findByUserId(userId);
    }

    @Override
    public Optional<Wallet> findByUserIdForUpdate(UUID userId) {
        return jpaRepository.findByUserIdForUpdate(userId);
    }
}
