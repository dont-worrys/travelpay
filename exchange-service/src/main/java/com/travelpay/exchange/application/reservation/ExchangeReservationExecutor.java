package com.travelpay.exchange.application.reservation;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.application.order.ExchangeOrderLedgerProcessor;
import com.travelpay.exchange.application.wallet.ExchangeWalletChecker;
import com.travelpay.exchange.domain.order.ChangedByType;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistoryRepository;
import com.travelpay.exchange.domain.quote.QuotePolicy;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.reservation.ExchangeReservation;
import com.travelpay.exchange.domain.reservation.ExchangeReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 조건에 닿은 예약 하나를 체결합니다. 순서는 보통 환전 신청과 같습니다.
 *
 * 1. 지갑 확인 (트랜잭션 밖. 원격 호출 동안 DB 커넥션·행 잠금을 잡지 않음)
 * 2. 한 트랜잭션: 예약 잠금(SKIP LOCKED) → 조건·만료 재확인 → 주문·생성 이력 저장 → 예약을 체결로(주문 ID와 함께)
 * 3. 커밋 뒤 원장 처리 (보통 환전과 같은 처리기. 실패해도 주문은 PROCESSING으로 남고 복구 스케줄러가 이어서 처리)
 *
 * 2를 한 트랜잭션으로 묶어 "주문은 있는데 예약은 대기"나 "체결인데 주문이 없음"이 생기지 않습니다.
 * 사용자 취소와 겹치면 먼저 잠근 쪽만 반영됩니다(취소가 먼저면 여기서 SKIPPED, 체결이 먼저면 취소가 409).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeReservationExecutor {

    private final ExchangeReservationRepository reservationRepository;
    private final ExchangeOrderRepository orderRepository;
    private final ExchangeOrderStatusHistoryRepository historyRepository;
    private final ExchangeWalletChecker walletChecker;
    private final ExchangeOrderLedgerProcessor ledgerProcessor;
    private final QuotePolicy quotePolicy;
    private final TransactionOperations transactionOperations;
    private final Clock clock;

    /**
     * 이 환율로 예약을 체결합니다. 체결할 수 없는 상태면 아무것도 바꾸지 않고 SKIPPED입니다.
     * 지갑 확인 실패는 아직 그대로 던집니다(예약은 대기로 남음). 실패별 처리는 #240 4-2단계에서 붙입니다.
     *
     * @param rate 조건을 충족시킨 수집 환율. 이 환율로 체결용 견적을 계산합니다
     */
    public ReservationExecution execute(UUID reservationId, ExchangeRate rate) {
        // 잠그지 않고 먼저 읽어 지갑 확인에 쓸 회원·지갑을 얻는다. 이 값들은 바뀌지 않는다
        Optional<ExchangeReservation> found = reservationRepository.findById(reservationId);
        if (found.isEmpty() || !executable(found.get(), rate, clock.instant())) {
            return ReservationExecution.skipped(reservationId);
        }
        ExchangeReservation reservation = found.get();
        walletChecker.check(reservation.getUserId(), reservation.getWalletId(),
                reservation.getFromCurrency(), reservation.getToCurrency());

        Optional<ExchangeOrder> placed = transactionOperations.execute(status -> placeOrder(reservationId, rate));
        if (placed.isEmpty()) {
            return ReservationExecution.skipped(reservationId);
        }
        log.info("예약 체결: reservationId={}, orderId={}, midRate={}", reservationId, placed.get().getId(), rate.getMidRate());
        return ReservationExecution.executed(reservationId, processLedger(placed.get()));
    }

    /**
     * 잠근 뒤 다시 확인합니다. 지갑 확인 동안 취소됐거나, 만료 시각이 지났을 수 있습니다.
     * 시각은 잠근 뒤에 다시 읽어 체결 시각·견적 발급 시각으로 씁니다.
     */
    private Optional<ExchangeOrder> placeOrder(UUID reservationId, ExchangeRate rate) {
        Optional<ExchangeReservation> locked = reservationRepository.lockWaiting(reservationId);
        Instant now = clock.instant();
        if (locked.isEmpty() || !executable(locked.get(), rate, now)) {
            return Optional.empty();
        }
        ExchangeReservation reservation = locked.get();
        ExchangeOrder order = orderRepository.save(reservation.placeOrder(rate, quotePolicy, now));
        historyRepository.save(order.placedHistory(ChangedByType.SYSTEM));
        if (!reservationRepository.markExecuted(reservationId, order.getId(), now)) {
            // 잠근 행이라 다른 트랜잭션이 바꿀 수 없다. 여기 오면 잠금이 깨진 것이라 주문까지 되돌린다
            throw new IllegalStateException("잠근 예약을 체결로 바꾸지 못했습니다: reservationId=" + reservationId);
        }
        return Optional.of(order);
    }

    private static boolean executable(ExchangeReservation reservation, ExchangeRate rate, Instant now) {
        return reservation.isTriggeredBy(rate) && !reservation.isExpired(now);
    }

    /** 주문은 이미 커밋됐으므로 원장 처리 중 예상 밖 오류가 나도 체결은 된 것입니다. 복구 스케줄러가 이어서 처리합니다. */
    private ExchangeOrder processLedger(ExchangeOrder order) {
        try {
            return ledgerProcessor.process(order);
        } catch (RuntimeException e) {
            log.error("예약 체결 주문의 원장 처리 중 오류: orderId={}, exception={}", order.getId(), e.getClass().getName(), e);
            return order;
        }
    }
}
