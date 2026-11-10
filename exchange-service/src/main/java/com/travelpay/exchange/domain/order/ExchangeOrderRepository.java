package com.travelpay.exchange.domain.order;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 환전 주문 저장소입니다. 구현은 infrastructure 계층이 담당합니다.
 * 주문 상태는 아래 조건부 UPDATE로만 바꿉니다. 모두 PROCESSING인 주문만 바꾸므로 여러 흐름이 동시에 불러도 한 번만 반영됩니다.
 */
public interface ExchangeOrderRepository {

    /**
     * 새 주문을 저장하고 바로 DB에 반영합니다. 제약 위반을 저장 시점에 알리기 위해서입니다.
     *
     * @throws DuplicateExchangeOrderException 같은 견적이나 같은 회원·멱등 키의 주문이 이미 있는 경우
     */
    ExchangeOrder save(ExchangeOrder order);

    Optional<ExchangeOrder> findById(UUID orderId);

    /** 같은 회원이 같은 멱등 키로 낸 주문입니다. 재요청인지 판단할 때 씁니다. */
    Optional<ExchangeOrder> findByUserIdAndIdempotencyKey(UUID userId, IdempotencyKey idempotencyKey);

    /** 요청자 본인의 주문만 찾습니다. 다른 사람의 주문은 없는 것과 같게 다룹니다. */
    Optional<ExchangeOrder> findByIdAndUserId(UUID orderId, UUID userId);

    /** 이 견적으로 만든 주문이 있는지 봅니다. 견적 조회의 사용 여부(USED)를 판단할 때 씁니다. 견적당 주문은 최대 1개(UNIQUE)입니다. */
    boolean existsByQuoteId(UUID quoteId);

    /**
     * 원장 호출을 선점합니다. PROCESSING이고, 확인 횟수가 조회했던 값과 같고, 마지막 확인 후 lease가 지났을 때만
     * 횟수를 1 올리고 확인 시각을 남깁니다.
     * true면 이번 원장 호출을 이 흐름이 맡은 것이고, false면 다른 흐름(첫 신청, 다른 서버의 스케줄러)이 먼저 맡았거나,
     * 다른 흐름의 원장 호출이 아직 진행 중일 수 있거나(lease 안), 이미 확정된 것입니다.
     * 횟수 비교는 같은 값을 읽은 흐름끼리의 경쟁을, lease는 앞 흐름이 선점한 뒤에 읽은 흐름의 중복 호출을 막습니다.
     *
     * @param seenCheckCount 주문을 읽었을 때의 확인 횟수
     * @param lease          선점 유효 기간. 원장 호출이 끝나는 최대 시간보다 길어야 호출이 겹치지 않습니다.
     */
    boolean claimLedgerCheck(UUID orderId, int seenCheckCount, Instant now, Duration lease);

    /**
     * PROCESSING인 주문만 settlement 값으로 바꿉니다. true면 이 호출이 바꾼 것이고, false면 이미 다른 흐름이 확정한 것입니다.
     * 상태 이력은 true일 때만 같은 트랜잭션에서 남깁니다.
     */
    boolean settleIfProcessing(UUID orderId, Settlement settlement, Instant now);

    /**
     * MANUAL_REVIEW인 주문만 관리자 처리 결과로 바꿉니다.
     * true면 이 호출이 바꾼 것이고, false면 이미 다른 흐름(다른 관리자)이 먼저 바꿨거나 MANUAL_REVIEW가 아닌 주문입니다.
     * 상태 이력은 true일 때만 같은 트랜잭션에서 남깁니다.
     */
    boolean resolveIfManualReview(UUID orderId, ManualReviewResolution resolution, Instant now);

    /**
     * 원장에 다시 보낼 PROCESSING 주문입니다. 마지막 확인(없으면 신청) 뒤 대기 시간이 지난 것만,
     * 확인 횟수가 적은 순, 오래 확인하지 않은 순으로 돌려줍니다.
     * 대기 시간은 30초 × 2^확인 횟수이고 최대 16분입니다(확인 5회부터).
     */
    List<ExchangeOrder> findRecoveryTargets(Instant now, int limit);

    /**
     * 신청 시각이 cutoff 이전(같은 시각 포함)인데 아직 PROCESSING인 주문 수입니다. 원장 결과를 오래 확정하지 못한(정체) 주문을 셉니다.
     * 상태는 바꾸지 않습니다. 재시도와 24시간 뒤 사람 확인으로 넘기는 규칙은 그대로입니다.
     */
    long countProcessingRequestedAtOrBefore(Instant cutoff);

    /** 위와 같은 정체 주문의 ID를 신청이 오래된 순으로 최대 limit개 돌려줍니다. 정체 알림 로그에 몇 건만 남길 때 씁니다. */
    List<UUID> findProcessingIdsRequestedAtOrBefore(Instant cutoff, int limit);

    /** 사람 확인(MANUAL_REVIEW)을 기다리는 주문 수입니다. */
    long countManualReview();
}
