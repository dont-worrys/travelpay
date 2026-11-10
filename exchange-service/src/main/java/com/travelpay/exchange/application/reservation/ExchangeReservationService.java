package com.travelpay.exchange.application.reservation;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.util.PageableUtils;
import com.travelpay.exchange.application.wallet.ExchangeWalletChecker;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.IdempotencyKey;
import com.travelpay.exchange.domain.quote.QuoteCurrencies;
import com.travelpay.exchange.domain.quote.QuotePolicy;
import com.travelpay.exchange.domain.reservation.DuplicateExchangeReservationException;
import com.travelpay.exchange.domain.reservation.ExchangeReservation;
import com.travelpay.exchange.domain.reservation.ExchangeReservationErrorCode;
import com.travelpay.exchange.domain.reservation.ExchangeReservationRepository;
import com.travelpay.exchange.domain.reservation.ReservationPolicy;
import com.travelpay.exchange.domain.reservation.ReservationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 예약 환전을 만들고, 조회하고, 취소합니다. 체결은 조건 매칭·체결 단계(#240 3·4단계)가 맡습니다.
 * 예약할 때는 돈이 움직이지 않으므로 원장을 부르지 않고, 지갑만 확인합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeReservationService {

    static final int MAX_PAGE_SIZE = 100;

    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final Set<String> LIST_SORT_FIELDS = Set.of("createdAt", "id");

    private final ExchangeReservationRepository reservationRepository;
    private final ExchangeReservationQueryRepository queryRepository;
    private final ExchangeOrderRepository orderRepository;
    private final ExchangeWalletChecker walletChecker;
    private final QuotePolicy quotePolicy;
    private final ReservationPolicy reservationPolicy;
    private final TransactionOperations transactionOperations;
    private final Clock clock;

    /**
     * 예약을 만듭니다. 같은 멱등 키 재요청은 견적·지갑 확인보다 먼저 찾아, 같은 내용이면 처음 예약을 돌려줍니다.
     * 처음 요청이면 입력을 검사하고(금액·목표 환율·만료), 대기 예약 수와 지갑을 확인한 뒤 저장합니다.
     *
     * 대기 예약 수는 두 번 셉니다. 지갑 확인(원격 호출) 전에 한 번 세어 한도에 찬 요청을 빨리 거절하고,
     * 저장하는 트랜잭션에서 회원 단위로 잠근 뒤 다시 세어 같은 회원의 동시 요청도 한도를 넘지 못하게 합니다.
     * 지갑 확인은 잠금 밖에서 해, 원격 호출 동안 DB 커넥션과 잠금을 잡지 않습니다.
     *
     * @throws BusinessException 멱등 키 형식 오류(400), 통화쌍·금액·목표 환율·만료 오류(400), 같은 키로 다른 예약(409),
     *                           대기 예약 수 초과(409), 지갑 없음(404)·사용 불가(409)·확인 실패(503)
     */
    public ExchangeReservationView reserve(ReserveCommand command) {
        IdempotencyKey key = new IdempotencyKey(command.idempotencyKey());
        QuoteCurrencies currencies = new QuoteCurrencies(command.fromCurrency(), command.toCurrency());
        Money amount = Money.of(command.fromCurrency(), command.amount());

        var existing = reservationRepository.findByUserIdAndIdempotencyKey(command.userId(), key);
        if (existing.isPresent()) {
            return replay(existing.get(), command, currencies, amount);
        }

        ExchangeReservation reservation = ExchangeReservation.reserve(command.userId(), command.walletId(), key,
                currencies, amount, command.targetRate(), command.expiresAt(), quotePolicy, reservationPolicy,
                clock.instant());
        checkWaitingLimit(command.userId());
        walletChecker.check(command.userId(), command.walletId(), currencies.from(), currencies.to());

        try {
            ExchangeReservation saved = transactionOperations.execute(status -> {
                reservationRepository.lockUserForReservation(command.userId());
                checkWaitingLimit(command.userId());
                return reservationRepository.save(reservation);
            });
            log.info("환전 예약: reservationId={}, userId={}, {} → {}, targetRate={}, expiresAt={}", saved.getId(),
                    saved.getUserId(), saved.getFromCurrency(), saved.getToCurrency(), saved.getTargetRate(),
                    saved.getExpiresAt());
            return new ExchangeReservationView(saved, null);
        } catch (DuplicateExchangeReservationException e) {
            // 같은 키의 동시 요청이 먼저 저장했다. 그 예약으로 재요청과 같이 응답한다
            ExchangeReservation winner = reservationRepository.findByUserIdAndIdempotencyKey(command.userId(), key)
                    .orElseThrow(() -> e);
            return replay(winner, command, currencies, amount);
        }
    }

    /** 본인 예약을 조회합니다. 체결됐으면 주문 상태를 함께 돌려줍니다. 없거나 다른 회원의 예약이면 404입니다. */
    @Transactional(readOnly = true)
    public ExchangeReservationView find(UUID userId, UUID reservationId) {
        ExchangeReservation reservation = reservationRepository.findByIdAndUserId(reservationId, userId)
                .orElseThrow(() -> new BusinessException(ExchangeReservationErrorCode.RESERVATION_NOT_FOUND));
        return new ExchangeReservationView(reservation, orderStatusOf(reservation));
    }

    /**
     * 본인 예약 목록을 예약 최신순으로 조회합니다. 정렬은 고정이고 같은 시각이면 예약 ID 역순입니다.
     * 목록에는 주문 상태를 붙이지 않습니다(행마다 주문을 조회하지 않게). 체결 결과는 상세에서 봅니다.
     *
     * @param status null이면 모든 상태
     * @throws BusinessException page가 음수이거나 size가 1보다 작으면 COMMON_INVALID_REQUEST(400). size는 최대 100으로 줄임
     */
    @Transactional(readOnly = true)
    public Page<ExchangeReservation> list(UUID userId, ReservationStatus status, int page, int size) {
        if (page < 0 || size < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        Pageable pageable = PageableUtils.toStablePageable(
                PageRequest.of(page, size, LIST_SORT), LIST_SORT_FIELDS, "id", MAX_PAGE_SIZE);
        return queryRepository.findPage(userId, status, pageable);
    }

    /**
     * 본인의 대기 예약을 취소합니다. 체결과 동시에 일어나도 조건부 UPDATE로 한쪽만 반영됩니다.
     *
     * @throws BusinessException 없거나 다른 회원의 예약(404), 이미 체결·취소·만료된 예약(409 EXCHANGE_RESERVATION_NOT_WAITING)
     */
    public ExchangeReservationView cancel(UUID userId, UUID reservationId) {
        Instant now = clock.instant();
        if (!reservationRepository.cancelIfWaiting(reservationId, userId, now)) {
            ExchangeReservation reservation = reservationRepository.findByIdAndUserId(reservationId, userId)
                    .orElseThrow(() -> new BusinessException(ExchangeReservationErrorCode.RESERVATION_NOT_FOUND));
            log.info("대기 중이 아닌 예약 취소 요청: reservationId={}, status={}", reservationId, reservation.getStatus());
            throw new BusinessException(ExchangeReservationErrorCode.NOT_WAITING);
        }
        log.info("환전 예약 취소: reservationId={}, userId={}", reservationId, userId);
        return find(userId, reservationId);
    }

    private void checkWaitingLimit(UUID userId) {
        if (reservationRepository.countWaitingByUserId(userId) >= reservationPolicy.maxWaitingPerUser()) {
            throw new BusinessException(ExchangeReservationErrorCode.TOO_MANY_WAITING);
        }
    }

    private ExchangeReservationView replay(ExchangeReservation existing, ReserveCommand command,
                                           QuoteCurrencies currencies, Money amount) {
        if (!existing.isSameRequest(command.walletId(), currencies, amount, command.targetRate(), command.expiresAt())) {
            throw new BusinessException(ExchangeReservationErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return new ExchangeReservationView(existing, orderStatusOf(existing));
    }

    private ExchangeOrderStatus orderStatusOf(ExchangeReservation reservation) {
        if (reservation.getOrderId() == null) {
            return null;
        }
        return orderRepository.findById(reservation.getOrderId()).map(ExchangeOrder::getStatus).orElse(null);
    }
}
