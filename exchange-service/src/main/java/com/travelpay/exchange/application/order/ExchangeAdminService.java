package com.travelpay.exchange.application.order;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.exchange.domain.ledger.LedgerClient;
import com.travelpay.exchange.domain.ledger.LedgerLookupResult;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderErrorCode;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistoryRepository;
import com.travelpay.exchange.domain.order.ManualReviewReason;
import com.travelpay.exchange.domain.order.ManualReviewResolution;
import com.travelpay.exchange.domain.order.ManualReviewResolution.Decision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 관리자(MASTER)가 MANUAL_REVIEW 환전 주문을 조회하고 처리하는 애플리케이션 서비스입니다.
 * 원장 조회를 통해 결과를 결정하며, 원장 호출은 DB 트랜잭션 밖에서 수행합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeAdminService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ExchangeOrderRepository orderRepository;
    private final ExchangeOrderStatusHistoryRepository historyRepository;
    private final ExchangeOrderQueryRepository orderQueryRepository;
    private final LedgerClient ledgerClient;
    private final Clock clock;
    private final TransactionOperations transactionOperations;

    /**
     * MANUAL_REVIEW 주문 목록을 오래된 순으로 조회합니다.
     */
    @Transactional(readOnly = true)
    public Page<ExchangeManualReviewView> getManualReviews(ManualReviewReason reviewReason, int page, int size) {
        if (page < 0 || size < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        int validSize = Math.min(size, MAX_PAGE_SIZE);
        return orderQueryRepository.findManualReviews(reviewReason, PageRequest.of(page, validSize));
    }

    /**
     * 단건 주문 및 원장 조회 결과, 예상 처리 결과를 조회합니다.
     */
    @Transactional(readOnly = true)
    public AdminExchangeReviewDetailResult getManualReview(UUID actorId, UUID orderId) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(orderId, "orderId");

        ExchangeOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ExchangeOrderErrorCode.ORDER_NOT_FOUND));
        if (order.getStatus() != ExchangeOrderStatus.MANUAL_REVIEW) {
            throw new BusinessException(ExchangeOrderErrorCode.NOT_MANUAL_REVIEW);
        }

        ManualReviewReason reviewReason = historyRepository.findLatestManualReviewReason(orderId)
                .orElseThrow(() -> new IllegalStateException("MANUAL_REVIEW 주문에 사유 이력이 없습니다: " + orderId));

        LedgerLookupResult lookup = ledgerClient.findByOrderId(orderId);
        if (lookup instanceof LedgerLookupResult.Unavailable unavailable) {
            log.warn("단건 조회 중 원장 조회 실패: orderId={}, cause={}", orderId, unavailable.cause());
            return AdminExchangeReviewDetailResult.lookupFailed(order, reviewReason);
        }

        Decision decision = ManualReviewResolution.decide(order, reviewReason, lookup);
        return AdminExchangeReviewDetailResult.of(order, reviewReason, lookup, decision);
    }

    /**
     * MANUAL_REVIEW 주문을 원장 조회 결과에 따라 확정 처리합니다.
     */
    public AdminExchangeResolveResult resolveManualReview(UUID actorId, UUID orderId, String memo) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(orderId, "orderId");

        // 1. 주문 검증 (트랜잭션 밖)
        ExchangeOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ExchangeOrderErrorCode.ORDER_NOT_FOUND));
        if (order.getStatus() != ExchangeOrderStatus.MANUAL_REVIEW) {
            throw new BusinessException(ExchangeOrderErrorCode.NOT_MANUAL_REVIEW);
        }

        ManualReviewReason reviewReason = historyRepository.findLatestManualReviewReason(orderId)
                .orElseThrow(() -> new IllegalStateException("MANUAL_REVIEW 주문에 사유 이력이 없습니다: " + orderId));

        // 2. 원장 조회 (트랜잭션 밖)
        LedgerLookupResult lookup = ledgerClient.findByOrderId(orderId);
        if (lookup instanceof LedgerLookupResult.Unavailable unavailable) {
            log.warn("원장 조회 실패로 MANUAL_REVIEW 처리를 진행할 수 없습니다: orderId={}, cause={}", orderId, unavailable.cause());
            throw new BusinessException(ExchangeOrderErrorCode.LEDGER_LOOKUP_UNAVAILABLE);
        }

        // 3. 처리 결정
        Decision decision = ManualReviewResolution.decide(order, reviewReason, lookup);
        if (decision instanceof Decision.EntryMismatch mismatch) {
            log.error("원장 보정 필요: 원장 거래 내용이 주문과 다릅니다: orderId={}, txId={}", orderId, mismatch.ledgerTransactionId());
            throw new BusinessException(ExchangeOrderErrorCode.LEDGER_CORRECTION_REQUIRED);
        }

        Decision.Resolvable resolvable = (Decision.Resolvable) decision;
        Instant now = clock.instant();
        ManualReviewResolution resolution = resolvable.toResolution(actorId, memo, now);

        // 4. 짧은 한 트랜잭션에서 조건부 UPDATE + 이력 저장
        return transactionOperations.execute(status -> {
            boolean updated = orderRepository.resolveIfManualReview(orderId, resolution, now);
            if (!updated) {
                // 다른 관리자가 먼저 처리했거나 상태가 변경됨
                throw new BusinessException(ExchangeOrderErrorCode.NOT_MANUAL_REVIEW);
            }
            historyRepository.save(resolution.history(orderId));
            log.info("MANUAL_REVIEW 주문 처리 완료: orderId={}, status={}, actorId={}, reason={}",
                    orderId, resolution.status(), actorId, resolution.reason());
            return new AdminExchangeResolveResult(
                    orderId,
                    resolution.status(),
                    resolution.failureReason(),
                    resolution.reason(),
                    resolution.orderLedgerTransactionId(),
                    now
            );
        });
    }
}
