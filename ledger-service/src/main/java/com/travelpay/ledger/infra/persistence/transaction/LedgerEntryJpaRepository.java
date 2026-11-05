package com.travelpay.ledger.infra.persistence.transaction;

import com.travelpay.ledger.domain.transaction.LedgerEntry;
import com.travelpay.ledger.domain.transaction.LedgerEntryLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryJpaRepository extends JpaRepository<LedgerEntry, UUID> {

    /**
     * 거래의 항목과 계정을 JOIN 한 번으로 읽는다 (항목마다 계정을 따로 읽지 않음).
     * 반영 순서는 Java UUID 비교로 정해져 DB 정렬로 재현할 수 없어 id 순으로 고정한다.
     */
    @Query("""
            select new com.travelpay.ledger.domain.transaction.LedgerEntryLine(
                a.ownerType, a.walletId, a.accountType, e.currency, e.direction, e.amount, e.entryType)
            from LedgerEntry e
            join LedgerAccount a on a.id = e.accountId
            where e.transactionId = :transactionId
            order by e.id asc
            """)
    List<LedgerEntryLine> findLinesByTransactionId(@Param("transactionId") UUID transactionId);
}
