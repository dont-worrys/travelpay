package com.travelpay.wallet.infra.persistence.charge;

import com.travelpay.wallet.domain.charge.ChargeFailureCode;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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

    /** transitionStatus + 단계 결과 ID 저장 + attempt_count 초기화 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChargeRequest c
               set c.status = :to,
                   c.failureCode = coalesce(:failureCode, c.failureCode),
                   c.lastError = coalesce(:lastError, c.lastError),
                   c.bankTxId = coalesce(:bankTxId, c.bankTxId),
                   c.ledgerRef = coalesce(:ledgerRef, c.ledgerRef),
                   c.refundBankTxId = coalesce(:refundBankTxId, c.refundBankTxId),
                   c.attemptCount = 0,
                   c.updatedAt = :now
             where c.id = :id
               and c.status = :from
            """)
    int advanceStatus(@Param("id") UUID id,
                      @Param("from") ChargeStatus from,
                      @Param("to") ChargeStatus to,
                      @Param("failureCode") ChargeFailureCode failureCode,
                      @Param("lastError") String lastError,
                      @Param("bankTxId") UUID bankTxId,
                      @Param("ledgerRef") UUID ledgerRef,
                      @Param("refundBankTxId") UUID refundBankTxId,
                      @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChargeRequest c
               set c.attemptCount = c.attemptCount + 1,
                   c.lastError = :lastError,
                   c.updatedAt = :now
             where c.id = :id
               and c.status = :status
            """)
    int increaseAttempt(@Param("id") UUID id,
                        @Param("status") ChargeStatus status,
                        @Param("lastError") String lastError,
                        @Param("now") Instant now);

    /** increaseAttempt 직후 같은 트랜잭션에서 읽는다 (행 잠금을 쥔 상태라 다른 트랜잭션이 바꾸지 못함) */
    @Query("select c.attemptCount from ChargeRequest c where c.id = :id")
    int findAttemptCount(@Param("id") UUID id);

    /**
     * 복구 대상: 처리 중 상태이고 대기 시간(30초 × 2^min(max(attempt_count-1, 0), 5))이 지난 건. 시간은 DB now() 기준.
     * 첫 번째 updated_at 조건은 대기 시간의 하한으로, (status, updated_at) 인덱스 범위 스캔을 쓰게 하려는 중복 조건이다.
     */
    @Query(nativeQuery = true, value = """
            select id, status, updated_at as "updatedAt"
              from charge_requests
             where status in ('REQUESTED', 'BANK_PROCESSING', 'CREDIT_PROCESSING', 'REFUND_PROCESSING')
               and updated_at < now() - interval '30 seconds'
               and updated_at < now() - interval '30 seconds' * power(2, least(greatest(attempt_count - 1, 0), 5))
             order by updated_at
             limit :limit
            """)
    List<ChargeRecoveryRow> findRecoveryTargets(@Param("limit") int limit);

    /** 복구 선점: 조회 이후 아무도 바꾸지 않았을 때만 updated_at을 DB now()로 갱신한다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update charge_requests
               set updated_at = now()
             where id = :id
               and status = :status
               and updated_at = :updatedAt
            """)
    int claimForRecovery(@Param("id") UUID id,
                         @Param("status") String status,
                         @Param("updatedAt") Instant updatedAt);
}
