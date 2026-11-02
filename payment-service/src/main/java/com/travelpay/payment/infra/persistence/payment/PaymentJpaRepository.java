package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentJpaRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
    Page<Payment> findByUserId(UUID userId, Pageable pageable);
    Page<Payment> findByUserIdAndStatus(UUID userId, PaymentStatus status, Pageable pageable);
    List<Payment> findByStatusAndUpdatedAtBefore(PaymentStatus status, Instant before, Pageable pageable);

    @Transactional
    @Modifying
    @Query("update Payment p set p.updatedAt = :retriedAt where p.id = :id and p.status = :status")
    int touchUpdatedAt(@Param("id") UUID id,
                       @Param("status") PaymentStatus status,
                       @Param("retriedAt") Instant retriedAt);

    long countByStatusAndCreatedAtBefore(PaymentStatus status, Instant before);

    @Query("select p.id from Payment p where p.status = :status and p.createdAt < :before order by p.createdAt")
    List<UUID> findIdsByStatusAndCreatedAtBefore(@Param("status") PaymentStatus status,
                                                 @Param("before") Instant before,
                                                 Pageable pageable);
}
