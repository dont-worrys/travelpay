package com.travelpay.wallet.infra.persistence.outbox;

import com.travelpay.wallet.domain.outbox.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = """
            SELECT *
              FROM outbox_events
             WHERE status = 'PENDING'
             ORDER BY retry_count ASC, created_at ASC, id ASC
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingForUpdate(@Param("limit") int limit);

    /** ids(비어 있지 않음) 중 PENDING인 행만 +1. 다른 트랜잭션이 잠근 행은 기다리지 않고 건너뛴다 */
    @Modifying
    @Query(value = """
            UPDATE outbox_events
               SET retry_count = retry_count + 1
             WHERE id IN (SELECT id
                            FROM outbox_events
                           WHERE id IN (:ids)
                             AND status = 'PENDING'
                             FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int increaseRetryCountIfPending(@Param("ids") Collection<UUID> ids);
}
