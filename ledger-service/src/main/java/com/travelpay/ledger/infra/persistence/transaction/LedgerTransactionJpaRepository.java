package com.travelpay.ledger.infra.persistence.transaction;

import com.travelpay.ledger.domain.transaction.LedgerTransaction;
import com.travelpay.ledger.domain.transaction.ReferenceType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionJpaRepository extends JpaRepository<LedgerTransaction, UUID> {

    Optional<LedgerTransaction> findByReferenceTypeAndReferenceId(ReferenceType referenceType, UUID referenceId);

    /** 기간 목록 첫 페이지. 정렬은 (createdAt, id) 오름차순 */
    @Query("""
            select t from LedgerTransaction t
            where t.referenceType = :referenceType
              and t.createdAt >= :from and t.createdAt < :to
            order by t.createdAt asc, t.id asc
            """)
    List<LedgerTransaction> findPeriodFirstPage(@Param("referenceType") ReferenceType referenceType,
                                                @Param("from") Instant from, @Param("to") Instant to,
                                                Limit limit);

    /**
     * 기간 목록 다음 페이지. 커서 위치 다음 행부터 읽도록 (createdAt, id) 행 비교를 쓴다.
     * 같은 createdAt 거래가 페이지 경계에 걸려도 id로 이어 받는다. 정렬과 비교가 같은 키라 중복·누락이 없다.
     */
    @Query("""
            select t from LedgerTransaction t
            where t.referenceType = :referenceType
              and t.createdAt >= :from and t.createdAt < :to
              and (t.createdAt, t.id) > (:afterCreatedAt, :afterId)
            order by t.createdAt asc, t.id asc
            """)
    List<LedgerTransaction> findPeriodPageAfter(@Param("referenceType") ReferenceType referenceType,
                                                @Param("from") Instant from, @Param("to") Instant to,
                                                @Param("afterCreatedAt") Instant afterCreatedAt,
                                                @Param("afterId") UUID afterId,
                                                Limit limit);
}
