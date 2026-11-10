package com.travelpay.exchange.application.reservation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorCode;
import com.travelpay.exchange.application.wallet.ExchangeWalletChecker;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.money.MoneyErrorCode;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderErrorCode;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.IdempotencyKey;
import com.travelpay.exchange.domain.quote.QuoteCurrencies;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.reservation.DuplicateExchangeReservationException;
import com.travelpay.exchange.domain.reservation.ExchangeReservation;
import com.travelpay.exchange.domain.reservation.ExchangeReservationErrorCode;
import com.travelpay.exchange.domain.reservation.ExchangeReservationRepository;
import com.travelpay.exchange.domain.reservation.ReservationPolicy;
import com.travelpay.exchange.domain.reservation.ReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExchangeReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID WALLET_ID = UUID.randomUUID();
    private static final String KEY = "key-1";
    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofDays(7));
    private static final ReservationPolicy POLICY = new ReservationPolicy(Duration.ofDays(30), 10);

    private final ExchangeReservationRepository reservationRepository = mock(ExchangeReservationRepository.class);
    private final ExchangeReservationQueryRepository queryRepository = mock(ExchangeReservationQueryRepository.class);
    private final ExchangeOrderRepository orderRepository = mock(ExchangeOrderRepository.class);
    private final ExchangeWalletChecker walletChecker = mock(ExchangeWalletChecker.class);
    private final ExchangeReservationService service = new ExchangeReservationService(reservationRepository,
            queryRepository, orderRepository, walletChecker, QuoteFixtures.policy(), POLICY,
            TransactionOperations.withoutTransaction(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(reservationRepository.findByUserIdAndIdempotencyKey(USER_ID, new IdempotencyKey(KEY)))
                .thenReturn(Optional.empty());
        when(reservationRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
    }

    @Test
    void reservesAfterCheckingWaitingCountAndWallet() {
        ExchangeReservationView view = service.reserve(command("100000", "830"));

        ArgumentCaptor<ExchangeReservation> saved = ArgumentCaptor.forClass(ExchangeReservation.class);
        verify(reservationRepository).save(saved.capture());
        assertEquals(ReservationStatus.WAITING, saved.getValue().getStatus());
        assertEquals(new Money(Currency.KRW, 100_000), saved.getValue().fromMoney());
        assertEquals(EXPIRES_AT, saved.getValue().getExpiresAt());
        assertNull(view.orderStatus());
        verify(walletChecker).check(USER_ID, WALLET_ID, Currency.KRW, Currency.JPY);
    }

    /** 같은 키·같은 내용의 재요청은 대기 수·지갑을 다시 확인하지 않고 처음 예약을 돌려준다 */
    @Test
    void replaysExistingReservationOfSameKey() {
        ExchangeReservation existing = reservation("830.00");
        when(reservationRepository.findByUserIdAndIdempotencyKey(USER_ID, new IdempotencyKey(KEY)))
                .thenReturn(Optional.of(existing));

        assertSame(existing, service.reserve(command("100000", "830")).reservation());
        verify(reservationRepository, never()).save(any());
        verify(reservationRepository, never()).countWaitingByUserId(any());
        verifyNoInteractions(walletChecker);
    }

    /**
     * 같은 키의 처음 요청이 잠금을 먼저 잡고 마지막 자리(10번째)를 채운 경우: 재요청은 잠금 뒤 세기 전에 같은 키 예약을 찾아
     * 한도 초과(409)가 아니라 처음 예약으로 응답한다.
     */
    @Test
    void retryWaitingOnLockGetsFirstReservationEvenIfItFilledLastSlot() {
        ExchangeReservation first = reservation("830.00");
        when(reservationRepository.findByUserIdAndIdempotencyKey(USER_ID, new IdempotencyKey(KEY)))
                .thenReturn(Optional.empty(), Optional.of(first));
        when(reservationRepository.countWaitingByUserId(USER_ID)).thenReturn(9L, 10L);

        assertSame(first, service.reserve(command("100000", "830")).reservation());
        verify(reservationRepository).countWaitingByUserId(USER_ID); // 잠근 뒤에는 세지 않음
        verify(reservationRepository, never()).save(any());
    }

    /** 처음 조회와 잠그기 전 확인 사이에 같은 키 요청이 마지막 자리를 채웠어도, 한도 초과가 아니라 처음 예약으로 응답한다 */
    @Test
    void retrySeeingFullLimitBeforeLockGetsFirstReservation() {
        ExchangeReservation first = reservation("830.00");
        when(reservationRepository.findByUserIdAndIdempotencyKey(USER_ID, new IdempotencyKey(KEY)))
                .thenReturn(Optional.empty(), Optional.of(first));
        when(reservationRepository.countWaitingByUserId(USER_ID)).thenReturn(10L);

        assertSame(first, service.reserve(command("100000", "830")).reservation());
        verifyNoInteractions(walletChecker);
        verify(reservationRepository, never()).lockUserForReservation(any());
    }

    @Test
    void rejectsSameKeyForAnotherReservation() {
        when(reservationRepository.findByUserIdAndIdempotencyKey(USER_ID, new IdempotencyKey(KEY)))
                .thenReturn(Optional.of(reservation("830")));

        assertError(ExchangeReservationErrorCode.IDEMPOTENCY_KEY_REUSED, () -> service.reserve(command("100000", "820")));
    }

    /** 입력 오류는 대기 수·지갑을 확인하기 전에 거절한다 */
    @Test
    void rejectsInvalidInputBeforeRemoteCalls() {
        assertError(ExchangeOrderErrorCode.INVALID_IDEMPOTENCY_KEY, () -> service.reserve(new ReserveCommand(USER_ID,
                " ", WALLET_ID, Currency.KRW, Currency.JPY, new BigDecimal("100000"), new BigDecimal("830"), EXPIRES_AT)));
        assertError(MoneyErrorCode.INVALID_AMOUNT, () -> service.reserve(command("999", "830")));
        assertError(ExchangeReservationErrorCode.INVALID_TARGET_RATE, () -> service.reserve(command("100000", "830.001")));

        verifyNoInteractions(walletChecker);
        verify(reservationRepository, never()).countWaitingByUserId(any());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void rejectsWhenWaitingReservationsReachLimit() {
        when(reservationRepository.countWaitingByUserId(USER_ID)).thenReturn(10L);

        assertError(ExchangeReservationErrorCode.TOO_MANY_WAITING, () -> service.reserve(command("100000", "830")));
        verifyNoInteractions(walletChecker);
        verify(reservationRepository, never()).save(any());
    }

    /** 지갑 확인 동안 다른 요청이 마지막 자리를 채웠으면, 회원 잠금 뒤 다시 센 값으로 거절하고 저장하지 않는다 */
    @Test
    void recountsUnderUserLockBeforeSaving() {
        when(reservationRepository.countWaitingByUserId(USER_ID)).thenReturn(9L, 10L);

        assertError(ExchangeReservationErrorCode.TOO_MANY_WAITING, () -> service.reserve(command("100000", "830")));

        InOrder order = inOrder(walletChecker, reservationRepository);
        order.verify(reservationRepository).countWaitingByUserId(USER_ID);
        order.verify(walletChecker).check(any(), any(), any(), any());
        order.verify(reservationRepository).lockUserForReservation(USER_ID);
        order.verify(reservationRepository).countWaitingByUserId(USER_ID);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void doesNotSaveWhenWalletCheckFails() {
        doThrow(new BusinessException(ExchangeOrderErrorCode.WALLET_NOT_AVAILABLE))
                .when(walletChecker).check(USER_ID, WALLET_ID, Currency.KRW, Currency.JPY);

        assertError(ExchangeOrderErrorCode.WALLET_NOT_AVAILABLE, () -> service.reserve(command("100000", "830")));
        verify(reservationRepository, never()).save(any());
    }

    /** 같은 키의 동시 요청이 먼저 저장했으면 그 예약으로 응답한다 */
    @Test
    void concurrentRequestOfSameKeyReturnsWinner() {
        ExchangeReservation winner = reservation("830");
        doThrow(new DuplicateExchangeReservationException("dup", null)).when(reservationRepository).save(any());
        when(reservationRepository.findByUserIdAndIdempotencyKey(USER_ID, new IdempotencyKey(KEY)))
                .thenReturn(Optional.empty(), Optional.of(winner));

        assertSame(winner, service.reserve(command("100000", "830")).reservation());
    }

    @Test
    void findsOwnReservationWithOrderStatusOnceExecuted() {
        ExchangeReservation executed = reservation("830");
        UUID orderId = UUID.randomUUID();
        ReflectionTestUtils.setField(executed, "status", ReservationStatus.EXECUTED);
        ReflectionTestUtils.setField(executed, "orderId", orderId);
        ExchangeOrder order = mock(ExchangeOrder.class);
        when(order.getStatus()).thenReturn(ExchangeOrderStatus.COMPLETED);
        when(reservationRepository.findByIdAndUserId(executed.getId(), USER_ID)).thenReturn(Optional.of(executed));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertEquals(ExchangeOrderStatus.COMPLETED, service.find(USER_ID, executed.getId()).orderStatus());
        assertError(ExchangeReservationErrorCode.RESERVATION_NOT_FOUND, () -> service.find(USER_ID, UUID.randomUUID()));
    }

    @Test
    void listsNewestFirstAndCapsPageSize() {
        when(queryRepository.findPage(eq(USER_ID), eq(ReservationStatus.WAITING), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.list(USER_ID, ReservationStatus.WAITING, 1, 500);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(queryRepository).findPage(eq(USER_ID), eq(ReservationStatus.WAITING), pageable.capture());
        assertEquals(1, pageable.getValue().getPageNumber());
        assertEquals(100, pageable.getValue().getPageSize());
        assertEquals(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")), pageable.getValue().getSort());
        assertError(CommonErrorCode.INVALID_REQUEST, () -> service.list(USER_ID, null, -1, 20));
        assertError(CommonErrorCode.INVALID_REQUEST, () -> service.list(USER_ID, null, 0, 0));
    }

    @Test
    void cancelsWaitingReservation() {
        ExchangeReservation canceled = reservation("830");
        ReflectionTestUtils.setField(canceled, "status", ReservationStatus.CANCELED);
        when(reservationRepository.cancelIfWaiting(canceled.getId(), USER_ID, NOW)).thenReturn(true);
        when(reservationRepository.findByIdAndUserId(canceled.getId(), USER_ID)).thenReturn(Optional.of(canceled));

        assertEquals(ReservationStatus.CANCELED, service.cancel(USER_ID, canceled.getId()).reservation().getStatus());
    }

    /** 취소가 반영되지 않으면, 예약이 있으면 409(대기 중 아님), 없으면 404로 구분한다 */
    @Test
    void cancelOfFinishedOrMissingReservationIsRejected() {
        ExchangeReservation executed = reservation("830");
        ReflectionTestUtils.setField(executed, "status", ReservationStatus.EXECUTED);
        when(reservationRepository.cancelIfWaiting(any(), eq(USER_ID), eq(NOW))).thenReturn(false);
        when(reservationRepository.findByIdAndUserId(executed.getId(), USER_ID)).thenReturn(Optional.of(executed));

        assertError(ExchangeReservationErrorCode.NOT_WAITING, () -> service.cancel(USER_ID, executed.getId()));
        assertError(ExchangeReservationErrorCode.RESERVATION_NOT_FOUND, () -> service.cancel(USER_ID, UUID.randomUUID()));
    }

    private static ReserveCommand command(String amount, String targetRate) {
        return new ReserveCommand(USER_ID, KEY, WALLET_ID, Currency.KRW, Currency.JPY, new BigDecimal(amount),
                new BigDecimal(targetRate), EXPIRES_AT);
    }

    private static ExchangeReservation reservation(String targetRate) {
        return withId(ExchangeReservation.reserve(USER_ID, WALLET_ID, new IdempotencyKey(KEY),
                new QuoteCurrencies(Currency.KRW, Currency.JPY), new Money(Currency.KRW, 100_000),
                new BigDecimal(targetRate), EXPIRES_AT, QuoteFixtures.policy(), POLICY, NOW));
    }

    /** 저장된 것처럼 ID를 채웁니다 (ID는 JPA가 저장할 때 만듦). */
    private static ExchangeReservation withId(ExchangeReservation reservation) {
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        return reservation;
    }

    private static void assertError(ErrorCode errorCode, Executable action) {
        assertEquals(errorCode, assertThrows(BusinessException.class, action).getErrorCode());
    }
}
