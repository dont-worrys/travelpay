package com.travelpay.exchange.application.reservation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.application.order.ExchangeOrderLedgerProcessor;
import com.travelpay.exchange.application.wallet.ExchangeWalletChecker;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.order.DuplicateExchangeOrderException;
import com.travelpay.exchange.domain.order.ExchangeOrderErrorCode;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistoryRepository;
import com.travelpay.exchange.domain.order.IdempotencyKey;
import com.travelpay.exchange.domain.quote.QuoteCurrencies;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.quote.QuotePolicy;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.reservation.ExchangeReservation;
import com.travelpay.exchange.domain.reservation.ExchangeReservationRepository;
import com.travelpay.exchange.domain.reservation.ReservationCancelReason;
import com.travelpay.exchange.domain.reservation.ReservationPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** DB로 만들기 어려운 실패(체결 시점 한도 변경, 주문 키 충돌, 예상 밖 지갑 오류)를 확인합니다. 정상 흐름은 통합 테스트가 봅니다. */
class ExchangeReservationExecutorTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID WALLET_ID = UUID.randomUUID();

    private final ExchangeReservationRepository reservationRepository = mock(ExchangeReservationRepository.class);
    private final ExchangeOrderRepository orderRepository = mock(ExchangeOrderRepository.class);
    private final ExchangeWalletChecker walletChecker = mock(ExchangeWalletChecker.class);
    private final ExchangeOrderLedgerProcessor ledgerProcessor = mock(ExchangeOrderLedgerProcessor.class);
    private final ExchangeReservation reservation = waitingReservation();
    private final ExchangeRate rate = QuoteFixtures.savedRate(ForeignCurrency.JPY, "820.00");

    /** 예약 뒤 1회 한도가 올라 10만 원이 최소 금액보다 작아진 경우: 다시 해도 같으므로 사유를 남기고 취소한다 */
    @Test
    void cancelsWhenAmountIsOutsideLimitAtExecution() {
        QuotePolicy raisedMinimum = QuoteFixtures.policyWith(Map.of(Currency.KRW,
                QuoteFixtures.range(Currency.KRW, 200_000, 2_000_000)));
        givenWaiting();

        ReservationExecution result = executor(raisedMinimum).execute(reservation.getId(), rate);

        assertEquals(ReservationExecution.Outcome.CANCELED, result.outcome());
        verify(reservationRepository).cancelBySystem(reservation.getId(), ReservationCancelReason.EXECUTION_REJECTED, NOW);
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(ledgerProcessor);
    }

    /** 주문 키가 이미 쓰였으면(회원 신청은 막으므로 생기면 안 되는 일) 되풀이하지 않게 취소한다 */
    @Test
    void cancelsWhenOrderKeyIsAlreadyTaken() {
        givenWaiting();
        doThrow(new DuplicateExchangeOrderException("dup", null)).when(orderRepository).save(any());
        when(reservationRepository.cancelBySystem(any(), any(), any())).thenReturn(true);

        ReservationExecution result = executor(QuoteFixtures.policy()).execute(reservation.getId(), rate);

        assertEquals(ReservationExecution.Outcome.CANCELED, result.outcome());
        verify(reservationRepository).cancelBySystem(reservation.getId(), ReservationCancelReason.EXECUTION_REJECTED, NOW);
        verify(reservationRepository, never()).markExecuted(any(), any(), any());
    }

    /** 지갑을 쓸 수 없어 취소하려는데 그사이 사용자가 먼저 취소했으면 SKIPPED다 */
    @Test
    void walletCancelLosingToUserCancelIsSkipped() {
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        doThrow(new BusinessException(ExchangeOrderErrorCode.WALLET_NOT_AVAILABLE)).when(walletChecker)
                .check(USER_ID, WALLET_ID, Currency.KRW, Currency.JPY);
        when(reservationRepository.cancelBySystem(reservation.getId(), ReservationCancelReason.WALLET_UNAVAILABLE, NOW))
                .thenReturn(false);

        assertEquals(ReservationExecution.Outcome.SKIPPED,
                executor(QuoteFixtures.policy()).execute(reservation.getId(), rate).outcome());
    }

    /** 원장 장애로 지갑이 잔액을 못 읽으면 지갑 서비스 장애와 같이 다음에 다시 시도한다 */
    @Test
    void ledgerOutageDuringWalletCheckIsRetriedLater() {
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        doThrow(new BusinessException(ExchangeOrderErrorCode.LEDGER_UNAVAILABLE)).when(walletChecker)
                .check(any(), any(), any(), any());

        assertEquals(ReservationExecution.Outcome.RETRY_LATER,
                executor(QuoteFixtures.policy()).execute(reservation.getId(), rate).outcome());
        verify(reservationRepository, never()).cancelBySystem(any(), any(), any());
        verify(reservationRepository, never()).lockWaiting(any());
    }

    /** 분류하지 않은 오류는 숨기지 않고 던진다. 예약은 바뀌지 않는다 */
    @Test
    void unexpectedWalletErrorIsThrown() {
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        doThrow(new IllegalStateException("bug")).when(walletChecker).check(any(), any(), any(), any());

        assertThrows(IllegalStateException.class,
                () -> executor(QuoteFixtures.policy()).execute(reservation.getId(), rate));
        verify(reservationRepository, never()).cancelBySystem(any(), any(), any());
    }

    private void givenWaiting() {
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(reservationRepository.lockWaiting(reservation.getId())).thenReturn(Optional.of(reservation));
        when(reservationRepository.markExecuted(eq(reservation.getId()), any(), eq(NOW))).thenReturn(true);
    }

    private ExchangeReservationExecutor executor(QuotePolicy quotePolicy) {
        return new ExchangeReservationExecutor(reservationRepository, orderRepository,
                mock(ExchangeOrderStatusHistoryRepository.class), walletChecker, ledgerProcessor, quotePolicy,
                TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ExchangeReservation waitingReservation() {
        ExchangeReservation reservation = ExchangeReservation.reserve(USER_ID, WALLET_ID, new IdempotencyKey("key-1"),
                new QuoteCurrencies(Currency.KRW, Currency.JPY), new Money(Currency.KRW, 100_000),
                new BigDecimal("830"), NOW.plus(Duration.ofDays(7)), QuoteFixtures.policy(),
                new ReservationPolicy(Duration.ofDays(30), 10), NOW.minus(Duration.ofDays(1)));
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        return reservation;
    }
}
