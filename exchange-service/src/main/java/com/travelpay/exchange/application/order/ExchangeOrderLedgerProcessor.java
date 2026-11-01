package com.travelpay.exchange.application.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.ledger.LedgerClient;
import com.travelpay.exchange.domain.ledger.LedgerPostResult;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistoryRepository;
import com.travelpay.exchange.domain.order.ManualReviewReason;
import com.travelpay.exchange.domain.order.Settlement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * PROCESSING 주문을 원장에 보내고 결과를 반영합니다. 신청, 같은 멱등 키 재요청, 복구 스케줄러가 모두 이 클래스를 거칩니다.
 *
 * 순서: 선점(확인 횟수 비교) → 원장 POST(트랜잭션 밖) → 결과 반영과 이력(짧은 트랜잭션).
 * 선점에 성공한 흐름만 원장을 부르므로, 여러 흐름이 같은 주문을 동시에 처리하려 해도 원장 호출은 한 번입니다.
 * 원장은 같은 reference를 한 번만 기록하므로, 결과를 모를 때는 같은 요청을 다시 보내기만 하면 됩니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeOrderLedgerProcessor {

    /** 신청 후 이 시간이 지나도 결과를 확인하지 못하면 사람 확인으로 보낸다 */
    static final Duration UNCONFIRMED_LIMIT = Duration.ofHours(24);

    private final ExchangeOrderRepository orderRepository;
    private final ExchangeOrderStatusHistoryRepository historyRepository;
    private final LedgerClient ledgerClient;
    private final TransactionOperations transactionOperations;
    private final Clock clock;

    /**
     * PROCESSING 주문을 원장에 보내고 최신 주문을 돌려줍니다. PROCESSING이 아니면 그대로 돌려줍니다.
     * 다른 흐름이 먼저 선점했거나 결과를 모르면 PROCESSING인 주문을 돌려주고, 다음 재요청이나 복구 스케줄러가 이어서 처리합니다.
     */
    public ExchangeOrder process(ExchangeOrder order) {
        if (order.getStatus() != ExchangeOrderStatus.PROCESSING) {
            return order;
        }
        UUID orderId = order.getId();
        Instant now = clock.instant();
        if (!now.isBefore(order.getRequestedAt().plus(UNCONFIRMED_LIMIT))) {
            log.error("오래 원장 결과를 확인하지 못해 사람 확인으로 보냅니다: orderId={}, checks={}",
                    orderId, order.getLedgerCheckCount());
            settle(orderId, Settlement.manualReview(ManualReviewReason.LEDGER_UNCONFIRMED_TOO_LONG), now);
            return reload(orderId);
        }
        if (!orderRepository.claimLedgerCheck(orderId, order.getLedgerCheckCount(), now)) {
            return reload(orderId);
        }
        LedgerPostResult result = ledgerClient.post(order.toLedgerPosting());
        Instant settledAt = clock.instant();
        Optional<Settlement> settlement = Settlement.from(result, settledAt);
        settlement.ifPresent(value -> settle(orderId, value, settledAt));
        return reload(orderId);
    }

    /** PROCESSING일 때만 바꾸고, 바꿨을 때만 같은 트랜잭션에서 이력을 남긴다. 다른 흐름이 먼저 바꿨으면 아무것도 하지 않는다 */
    private void settle(UUID orderId, Settlement settlement, Instant now) {
        transactionOperations.executeWithoutResult(status -> {
            if (orderRepository.settleIfProcessing(orderId, settlement, now)) {
                historyRepository.save(settlement.history(orderId));
            }
        });
    }

    private ExchangeOrder reload(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("처리 중인 주문을 다시 찾지 못했습니다: " + orderId));
    }
}
