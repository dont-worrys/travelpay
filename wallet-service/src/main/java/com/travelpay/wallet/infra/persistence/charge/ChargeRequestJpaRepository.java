package com.travelpay.wallet.infra.persistence.charge;

import com.travelpay.wallet.domain.charge.ChargeFailureCode;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
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

    /**
     * 운영자 보정 반영 전 행 잠금. MANUAL_REVIEW일 때만 잠그고 id를 돌려준다 (READ COMMITTED에서 대기 후 조건을 다시 본다).
     * 보정 기록 INSERT의 FK 확인(KEY SHARE)과 UNIQUE 칸을 바꾸는 UPDATE(키 갱신 잠금)가 동시 보정끼리 교착되지 않도록
     * 트랜잭션 첫 단계에서 잠근다
     */
    @Query(nativeQuery = true, value = """
            select id from charge_requests
             where id = :id
               and status = 'MANUAL_REVIEW'
               for update
            """)
    List<UUID> lockManualReview(@Param("id") UUID id);

    /**
     * 운영자 보정(확정): 현재 상태가 from(MANUAL_REVIEW)일 때만 to로 바꾼다. failure_code는 넘긴 값으로 바꾸고(null이면 비움)
     * attempt_count는 그대로. 거래 ID는 비어 있을 때만 확인한 값으로 채운다 (기존 값과 다른지는 호출자가 먼저 확인)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChargeRequest c
               set c.status = :to,
                   c.failureCode = :failureCode,
                   c.bankTxId = coalesce(c.bankTxId, :bankTxId),
                   c.ledgerRef = coalesce(c.ledgerRef, :ledgerRef),
                   c.refundBankTxId = coalesce(c.refundBankTxId, :refundBankTxId),
                   c.updatedAt = :now
             where c.id = :id
               and c.status = :from
            """)
    int confirmResolution(@Param("id") UUID id,
                          @Param("from") ChargeStatus from,
                          @Param("to") ChargeStatus to,
                          @Param("failureCode") ChargeFailureCode failureCode,
                          @Param("bankTxId") UUID bankTxId,
                          @Param("ledgerRef") UUID ledgerRef,
                          @Param("refundBankTxId") UUID refundBankTxId,
                          @Param("now") Instant now);

    /**
     * 운영자 보정(재개): confirmResolution에 더해 attempt_count를 0으로 초기화한다.
     * failure_code는 정상 흐름에서 그 단계에 들어올 때의 값(넘긴 값, null이면 비움)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChargeRequest c
               set c.status = :to,
                   c.failureCode = :failureCode,
                   c.attemptCount = 0,
                   c.bankTxId = coalesce(c.bankTxId, :bankTxId),
                   c.ledgerRef = coalesce(c.ledgerRef, :ledgerRef),
                   c.refundBankTxId = coalesce(c.refundBankTxId, :refundBankTxId),
                   c.updatedAt = :now
             where c.id = :id
               and c.status = :from
            """)
    int resumeResolution(@Param("id") UUID id,
                         @Param("from") ChargeStatus from,
                         @Param("to") ChargeStatus to,
                         @Param("failureCode") ChargeFailureCode failureCode,
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
     * 복구 대상: 상태가 statuses(처리 중 상태 중 한 단계 묶음) 중 하나이고 대기 시간(30초 × 2^min(max(attempt_count-1, 0), 5))이 지난 건. 시간은 DB now() 기준.
     * 첫 번째 updated_at 조건은 대기 시간의 하한으로, (status, updated_at) 인덱스 범위 스캔을 쓰게 하려는 중복 조건이다.
     */
    @Query(nativeQuery = true, value = """
            select id, status, updated_at as "updatedAt", bank_code as "bankCode"
              from charge_requests
             where status in (:statuses)
               and updated_at < now() - interval '30 seconds'
               and updated_at < now() - interval '30 seconds' * power(2, least(greatest(attempt_count - 1, 0), 5))
             order by updated_at
             limit :limit
            """)
    List<ChargeRecoveryRow> findRecoveryTargets(@Param("statuses") Collection<String> statuses,
                                                @Param("limit") int limit);

    /** findRecoveryTargets와 같고 excludedBankCodes(비어 있지 않음)의 은행 코드인 충전을 뺀다 */
    @Query(nativeQuery = true, value = """
            select id, status, updated_at as "updatedAt", bank_code as "bankCode"
              from charge_requests
             where status in (:statuses)
               and bank_code not in (:excludedBankCodes)
               and updated_at < now() - interval '30 seconds'
               and updated_at < now() - interval '30 seconds' * power(2, least(greatest(attempt_count - 1, 0), 5))
             order by updated_at
             limit :limit
            """)
    List<ChargeRecoveryRow> findRecoveryTargetsExcludingBanks(@Param("statuses") Collection<String> statuses,
                                                              @Param("excludedBankCodes") Collection<String> excludedBankCodes,
                                                              @Param("limit") int limit);

    /** 멈춘 충전 지표. 생성 시각 기준 (updated_at은 재시도마다 갱신되어 오래 멈춘 건이 빠짐) */
    long countByStatusInAndCreatedAtBefore(Collection<ChargeStatus> statuses, Instant createdAt);

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

    /**
     * 회원 충전 목록. userId는 OR 없이 항상 걸어 ix_charge_requests_user_created를 쓴다.
     * 정렬은 Pageable이 붙이고, countQuery는 같은 조건에 ORDER BY 없이 센다
     */
    @Query(value = """
            select c from ChargeRequest c
             where c.userId = :userId
               and (:status is null or c.status = :status)
               and (cast(:from as Instant) is null or c.createdAt >= :from)
               and (cast(:to as Instant) is null or c.createdAt < :to)
            """,
            countQuery = """
            select count(c) from ChargeRequest c
             where c.userId = :userId
               and (:status is null or c.status = :status)
               and (cast(:from as Instant) is null or c.createdAt >= :from)
               and (cast(:to as Instant) is null or c.createdAt < :to)
            """)
    Page<ChargeRequest> findByUser(@Param("userId") UUID userId,
                                   @Param("status") ChargeStatus status,
                                   @Param("from") Instant from,
                                   @Param("to") Instant to,
                                   Pageable pageable);

    /** 전체 충전 목록(관리자). 정렬은 Pageable이 붙이고, countQuery는 같은 조건에 ORDER BY 없이 센다 */
    @Query(value = """
            select c from ChargeRequest c
             where (:userId is null or c.userId = :userId)
               and (:status is null or c.status = :status)
               and (cast(:from as Instant) is null or c.createdAt >= :from)
               and (cast(:to as Instant) is null or c.createdAt < :to)
            """,
            countQuery = """
            select count(c) from ChargeRequest c
             where (:userId is null or c.userId = :userId)
               and (:status is null or c.status = :status)
               and (cast(:from as Instant) is null or c.createdAt >= :from)
               and (cast(:to as Instant) is null or c.createdAt < :to)
            """)
    Page<ChargeRequest> search(@Param("userId") UUID userId,
                               @Param("status") ChargeStatus status,
                               @Param("from") Instant from,
                               @Param("to") Instant to,
                               Pageable pageable);

    /** 대사 후보. ix_charge_requests_created 범위 스캔 */
    @Query("select c from ChargeRequest c where c.createdAt >= :from and c.createdAt < :to")
    List<ChargeRequest> findCreatedBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** 은행 requestId로 충전 찾기. 두 컬럼 모두 UNIQUE 인덱스가 있다 */
    @Query("""
            select c from ChargeRequest c
             where c.bankRequestId in :requestIds
                or c.refundRequestId in :requestIds
            """)
    List<ChargeRequest> findByBankOrRefundRequestIds(@Param("requestIds") Collection<String> requestIds);
}
