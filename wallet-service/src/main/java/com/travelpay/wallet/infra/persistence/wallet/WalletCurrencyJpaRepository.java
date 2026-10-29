package com.travelpay.wallet.infra.persistence.wallet;

import com.travelpay.wallet.domain.wallet.WalletCurrency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface WalletCurrencyJpaRepository extends JpaRepository<WalletCurrency, UUID> {

    /** 동시 요청에도 uk_wallet_currencies_wallet_currency 기준으로 통화별 1건만 생긴다. 반환값은 추가된 행 수(0 또는 1) */
    @Modifying
    @Query(value = """
            INSERT INTO wallet_currencies
                (id, wallet_id, currency, ledger_account_id, status, created_at, updated_at)
            VALUES
                (:id, :walletId, :currency, :ledgerAccountId, 'ACTIVE', now(), now())
            ON CONFLICT (wallet_id, currency) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("walletId") UUID walletId,
                       @Param("currency") String currency,
                       @Param("ledgerAccountId") UUID ledgerAccountId);

    List<WalletCurrency> findAllByWalletId(UUID walletId);
}
