package com.travelpay.exchange.application.reservation;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.ErrorCode;
import com.travelpay.exchange.application.order.ExchangeOrderLedgerProcessor;
import com.travelpay.exchange.application.reservation.ReservationExecution.Outcome;
import com.travelpay.exchange.application.wallet.ExchangeWalletChecker;
import com.travelpay.exchange.domain.order.ChangedByType;
import com.travelpay.exchange.domain.order.DuplicateExchangeOrderException;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderErrorCode;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistoryRepository;
import com.travelpay.exchange.domain.quote.QuotePolicy;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.reservation.ExchangeReservation;
import com.travelpay.exchange.domain.reservation.ExchangeReservationRepository;
import com.travelpay.exchange.domain.reservation.ReservationCancelReason;
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
 *
 * 체결하지 못하는 경우는 다시 해도 될지로 나눕니다.
 * - 지갑 서비스·원장 장애: 잠깐의 장애라 대기로 남기고 다음 수집 때 다시 시도합니다(RETRY_LATER).
 * - 지갑 없음·사용 불가, 체결 시점 한도 밖: 다시 해도 같은 결과라 사유를 남기고 취소합니다(CANCELED).
 *   취소하지 않으면 환율이 수집될 때마다 같은 실패를 되풀이합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeReservationExecutor {

    /** 지금은 확인할 수 없을 뿐인 장애. 다음 수집 때 다시 시도 */
    private static final Set<ErrorCode> RETRYABLE_WALLET_ERRORS = Set.of(
            ExchangeOrderErrorCode.WALLET_SERVICE_UNAVAILABLE, ExchangeOrderErrorCode.LEDGER_UNAVAILABLE);
    /** 지갑이 없거나(해지) 그 통화로 환전할 수 없음. 다시 해도 같은 결과 */
    private static final Set<ErrorCode> UNAVAILABLE_WALLET_ERRORS = Set.of(
            ExchangeOrderErrorCode.WALLET_NOT_FOUND, ExchangeOrderErrorCode.WALLET_NOT_AVAILABLE);

    private final ExchangeReservationRepository reservationRepository;
    private final ExchangeOrderRepository orderRepository;
    private final ExchangeOrderStatusHistoryRepository historyRepository;
    private final ExchangeWalletChecker walletChecker;
    private final ExchangeOrderLedgerProcessor ledgerProcessor;
    private final QuotePolicy quotePolicy;
    private final TransactionOperations transactionOperations;
    private final Clock clock;

    /**
     * 이 환율로 예약을 체결합니다. 결과는 ReservationExecution.Outcome을 봅니다.
     * DB 장애처럼 예상하지 못한 오류는 던집니다. 그때 예약은 대기로 남습니다(트랜잭션이 롤백되므로).
     *
     * @param rate 조건을 충족시킨 수집 환율. 이 환율로 체결용 견적을 계산합니다
     */
    public ReservationExecution execute(UUID reservationId, ExchangeRate rate) {
        // 잠그지 않고 먼저 읽어 지갑 확인에 쓸 회원·지갑을 얻는다. 이 값들은 바뀌지 않는다
        Optional<ExchangeReservation> found = reservationRepository.findById(reservationId);
        if (found.isEmpty() || !executable(found.get(), rate, clock.instant())) {
            return ReservationExecution.of(reservationId, Outcome.SKIPPED);
        }
        Optional<Outcome> walletProblem = checkWallet(found.get());
        if (walletProblem.isPresent()) {
            return ReservationExecution.of(reservationId, walletProblem.get());
        }

        Placement placement;
        try {
            placement = transactionOperations.execute(status -> place(reservationId, rate));
        } catch (DuplicateExchangeOrderException e) {
            // 이 예약의 주문 키를 다른 주문이 이미 쓰고 있다. 회원 신청은 이 키를 막으므로 생기면 안 되는 일이고,
            // 다시 해도 같은 결과라 취소한다. 주문 INSERT가 실패한 트랜잭션은 롤백됐으므로 새 트랜잭션에서 바꾼다
            log.error("예약 체결 주문 키가 이미 쓰였습니다: reservationId={}", reservationId, e);
            return cancel(reservationId, ReservationCancelReason.EXECUTION_REJECTED);
        }
        if (placement.order() == null) {
            return ReservationExecution.of(reservationId, placement.outcome());
        }
        log.info("예약 체결: reservationId={}, orderId={}, midRate={}",
                reservationId, placement.order().getId(), rate.getMidRate());
        return ReservationExecution.executed(reservationId, processLedger(placement.order()));
    }

    /** 지갑을 쓸 수 있으면 empty, 아니면 이번 체결의 결과입니다. */
    private Optional<Outcome> checkWallet(ExchangeReservation reservation) {
        try {
            walletChecker.check(reservation.getUserId(), reservation.getWalletId(),
                    reservation.getFromCurrency(), reservation.getToCurrency());
            return Optional.empty();
        } catch (BusinessException e) {
            if (RETRYABLE_WALLET_ERRORS.contains(e.getErrorCode())) {
                log.warn("지갑을 확인하지 못해 다음 수집 때 다시 체결합니다: reservationId={}, reason={}",
                        reservation.getId(), e.getErrorCode());
                return Optional.of(Outcome.RETRY_LATER);
            }
            if (UNAVAILABLE_WALLET_ERRORS.contains(e.getErrorCode())) {
                log.info("지갑을 쓸 수 없어 예약을 취소합니다: reservationId={}, reason={}",
                        reservation.getId(), e.getErrorCode());
                return Optional.of(cancel(reservation.getId(), ReservationCancelReason.WALLET_UNAVAILABLE).outcome());
            }
            throw e;
        }
    }

    /**
     * 잠근 뒤 다시 확인합니다. 지갑 확인 동안 취소됐거나, 만료 시각이 지났을 수 있습니다.
     * 시각은 잠근 뒤에 다시 읽어 체결 시각·견적 발급 시각으로 씁니다.
     * 체결 시점 규칙으로 주문을 만들 수 없으면(한도 밖) 같은 트랜잭션에서 사유를 남기고 취소합니다.
     */
    private Placement place(UUID reservationId, ExchangeRate rate) {
        Optional<ExchangeReservation> locked = reservationRepository.lockWaiting(reservationId);
        Instant now = clock.instant();
        if (locked.isEmpty() || !executable(locked.get(), rate, now)) {
            return new Placement(Outcome.SKIPPED, null);
        }
        ExchangeOrder order;
        try {
            order = locked.get().placeOrder(rate, quotePolicy, now);
        } catch (BusinessException e) {
            log.info("체결 시점 규칙으로 주문을 만들 수 없어 예약을 취소합니다: reservationId={}, reason={}",
                    reservationId, e.getErrorCode());
            reservationRepository.cancelBySystem(reservationId, ReservationCancelReason.EXECUTION_REJECTED, now);
            return new Placement(Outcome.CANCELED, null);
        }
        ExchangeOrder saved = orderRepository.save(order);
        historyRepository.save(saved.placedHistory(ChangedByType.SYSTEM));
        if (!reservationRepository.markExecuted(reservationId, saved.getId(), now)) {
            // 잠근 행이라 다른 트랜잭션이 바꿀 수 없다. 여기 오면 잠금이 깨진 것이라 주문까지 되돌린다
            throw new IllegalStateException("잠근 예약을 체결로 바꾸지 못했습니다: reservationId=" + reservationId);
        }
        return new Placement(Outcome.EXECUTED, saved);
    }

    /** 대기 중일 때만 취소합니다. 그사이 사용자가 취소했거나 다른 곳이 체결했으면 SKIPPED입니다. */
    private ReservationExecution cancel(UUID reservationId, ReservationCancelReason reason) {
        boolean canceled = reservationRepository.cancelBySystem(reservationId, reason, clock.instant());
        return ReservationExecution.of(reservationId, canceled ? Outcome.CANCELED : Outcome.SKIPPED);
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

    /** 트랜잭션 안의 결과. 체결했으면 order가 있다 */
    private record Placement(Outcome outcome, ExchangeOrder order) {
    }
}
