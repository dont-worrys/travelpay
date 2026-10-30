package com.travelpay.wallet.infra.persistence.charge;

import com.travelpay.wallet.domain.charge.ChargeFailureCode;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ChargeRequestJpaRepository extends JpaRepository<ChargeRequest, UUID> {

    Optional<ChargeRequest> findByUserIdAndIdempotencyKey(UUID userId, UUID idempotencyKey);

    /**
     * 조건부 상태 전이. 벌크 UPDATE는 @PreUpdate가 동작하지 않으므로 updated_at을 직접 갱신한다.
     * 실행 전 flush, 실행 후 영속성 컨텍스트를 비워 이전에 읽은 엔티티가 옛 상태로 남지 않게 한다.
     * coalesce의 null 파라미터 타입은 반대쪽 컬럼에서 정해진다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChargeRequest c
               set c.status = :to,
                   c.failureCode = coalesce(:failureCode, c.failureCode),
                   c.lastError = coalesce(:lastError, c.lastError),
                   c.updatedAt = :now
             where c.id = :id
               and c.status = :from
            """)
    int transitionStatus(@Param("id") UUID id,
                         @Param("from") ChargeStatus from,
                         @Param("to") ChargeStatus to,
                         @Param("failureCode") ChargeFailureCode failureCode,
                         @Param("lastError") String lastError,
                         @Param("now") Instant now);
}
