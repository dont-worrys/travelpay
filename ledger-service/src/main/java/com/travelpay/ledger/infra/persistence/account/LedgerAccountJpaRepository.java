package com.travelpay.ledger.infra.persistence.account;

import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.account.AccountType;
import com.travelpay.ledger.domain.account.LedgerAccount;
import com.travelpay.ledger.domain.account.OwnerType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountJpaRepository extends JpaRepository<LedgerAccount, UUID> {

    /** 동시 요청에도 uk_ledger_accounts_wallet 기준으로 1건만 생긴다. 반환값은 추가된 행 수(0 또는 1) */
    @Modifying
    @Query(value = """
            INSERT INTO ledger_accounts
                (id, owner_type, wallet_id, currency, account_type, balance, status, created_at, updated_at)
            VALUES
                (:id, 'USER', :walletId, :currency, 'WALLET', 0, 'ACTIVE', now(), now())
            ON CONFLICT (wallet_id, account_type, currency) WHERE wallet_id IS NOT NULL DO NOTHING
            """, nativeQuery = true)
    int insertWalletAccountIfAbsent(@Param("id") UUID id,
                                    @Param("walletId") UUID walletId,
                                    @Param("currency") String currency);

    List<LedgerAccount> findAllByWalletIdAndAccountType(UUID walletId, AccountType accountType);

    @Query("""
            select a.id from LedgerAccount a
            where a.ownerType = :ownerType and a.walletId = :walletId
              and a.accountType = :accountType and a.currency = :currency
            """)
    Optional<UUID> findUserAccountId(@Param("ownerType") OwnerType ownerType,
                                     @Param("walletId") UUID walletId,
                                     @Param("accountType") AccountType accountType,
                                     @Param("currency") Currency currency);

    @Query("""
            select a.id from LedgerAccount a
            where a.ownerType = :ownerType and a.accountType = :accountType and a.currency = :currency
            """)
    Optional<UUID> findSystemAccountId(@Param("ownerType") OwnerType ownerType,
                                       @Param("accountType") AccountType accountType,
                                       @Param("currency") Currency currency);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LedgerAccount a where a.id in :ids order by a.id")
    List<LedgerAccount> lockAllByIdInOrderById(@Param("ids") Collection<UUID> ids);
}
