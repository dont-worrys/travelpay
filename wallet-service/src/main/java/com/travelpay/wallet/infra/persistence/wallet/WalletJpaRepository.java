package com.travelpay.wallet.infra.persistence.wallet;

import com.travelpay.wallet.domain.wallet.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletJpaRepository extends JpaRepository<Wallet, UUID> {

    /** 동시 요청에도 uk_wallets_user 기준으로 1건만 생긴다. 반환값은 추가된 행 수(0 또는 1) */
    @Modifying
    @Query(value = """
            INSERT INTO wallets (id, user_id, status, version, created_at, updated_at)
            VALUES (:id, :userId, 'PENDING', 0, now(), now())
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId);

    Optional<Wallet> findByUserId(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId")
    Optional<Wallet> findByUserIdForUpdate(@Param("userId") UUID userId);
}
