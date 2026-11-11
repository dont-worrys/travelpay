package com.travelpay.wallet.infra.client.bank;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.charge.dto.BankResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.travelpay.wallet.infra.client.FeignFailures.error;
import static com.travelpay.wallet.infra.client.FeignFailures.timeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 은행 코드별 서킷: 결과 모름만 실패로 세고, 열리면 보내지 않으며, Feign 밖 예외는 기록 없이 허가만 반납한다 */
class BankClientAdapterCircuitBreakerTest {

    private static final String BANK_A = "004";
    private static final String BANK_B = "088";
    private static final String REQUEST_ID = "charge-1";

    private final BankFeignClient feignClient = mock(BankFeignClient.class);
    private final AtomicLong fakeNanos = new AtomicLong();
    private final CircuitBreakerRegistry registry = BankCircuitBreakerConfig.registry(
            CircuitBreakerConfig.from(BankCircuitBreakerConfig.config(BankCircuitBreakerTestSupport.properties()))
                    .currentTimestampFunction(clock -> fakeNanos.get(), TimeUnit.NANOSECONDS)
                    .build(),
            new SimpleMeterRegistry());
    private final BankClientAdapter adapter = adapter(registry);

    @Test
    void 결과_모름이_5번_연달아_나오면_열리고_그_은행에는_보내지_않는다() {
        when(feignClient.withdraw(any(), any())).thenThrow(timeout());

        for (int i = 0; i < 4; i++) {
            assertInstanceOf(BankResult.Unknown.class, withdraw(BANK_A));
            assertEquals(CircuitBreaker.State.CLOSED, state(BANK_A));
        }
        assertInstanceOf(BankResult.Unknown.class, withdraw(BANK_A));
        assertEquals(CircuitBreaker.State.OPEN, state(BANK_A));

        assertEquals(new BankResult.NotSent(BANK_A), withdraw(BANK_A));
        verify(feignClient, times(5)).withdraw(any(), any());
    }

    @Test
    void 업무_거절과_응답_이상은_은행이_답한_것이라_실패로_세지_않는다() {
        when(feignClient.withdraw(any(), any()))
                .thenThrow(timeout(), timeout(), timeout(), timeout())
                .thenThrow(error(409, "BANK_DUPLICATE_REQUEST"))
                .thenReturn(ApiResponse.success(
                        new BankTransactionResponse(null, REQUEST_ID, "DEBIT", "FAILED", "INSUFFICIENT_BALANCE")));

        for (int i = 0; i < 4; i++) {
            withdraw(BANK_A);
        }
        assertInstanceOf(BankResult.Invalid.class, withdraw(BANK_A));
        assertInstanceOf(BankResult.Failed.class, withdraw(BANK_A));

        assertEquals(CircuitBreaker.State.CLOSED, state(BANK_A));
        assertEquals(2, registry.circuitBreaker(BANK_A).getMetrics().getNumberOfSuccessfulCalls());
    }

    @Test
    void 한_은행의_서킷이_열려도_다른_은행에는_보낸다() {
        registry.circuitBreaker(BANK_A).transitionToOpenState();
        givenWithdrawSucceeded();

        assertEquals(new BankResult.NotSent(BANK_A), withdraw(BANK_A));
        assertInstanceOf(BankResult.Succeeded.class, withdraw(BANK_B));

        verify(feignClient, times(1)).withdraw(any(), any());
        assertEquals(CircuitBreaker.State.CLOSED, state(BANK_B));
    }

    @Test
    void 환불도_같은_은행_서킷을_거친다() {
        when(feignClient.withdraw(any(), any())).thenThrow(timeout());
        for (int i = 0; i < 5; i++) {
            withdraw(BANK_A);
        }

        assertEquals(new BankResult.NotSent(BANK_A), adapter.refund(REQUEST_ID, BANK_A, UUID.randomUUID(), 10_000));
        verify(feignClient, never()).refund(any());
    }

    @Test
    void 반열림_시험_호출이_성공하면_닫힌다() {
        halfOpen(BANK_A);
        givenWithdrawSucceeded();

        assertInstanceOf(BankResult.Succeeded.class, withdraw(BANK_A));

        assertEquals(CircuitBreaker.State.CLOSED, state(BANK_A));
    }

    @Test
    void 반열림_시험_호출이_결과_모름이면_다시_열린다() {
        halfOpen(BANK_A);
        when(feignClient.withdraw(any(), any())).thenThrow(timeout());

        assertInstanceOf(BankResult.Unknown.class, withdraw(BANK_A));

        assertEquals(CircuitBreaker.State.OPEN, state(BANK_A));
    }

    @Test
    void 반열림에서는_시험_호출_1건만_보내고_나머지는_보내지_않는다() {
        halfOpen(BANK_A);
        // 시험 호출이 끝나기 전에 같은 은행 호출이 하나 더 오는 경우
        when(feignClient.withdraw(any(), any())).thenAnswer(invocation -> {
            assertEquals(new BankResult.NotSent(BANK_A), withdraw(BANK_A));
            return ApiResponse.success(succeeded());
        });

        assertInstanceOf(BankResult.Succeeded.class, withdraw(BANK_A));
        verify(feignClient, times(1)).withdraw(any(), any());
    }

    // --- 허가 반납 조건 (가짜 서킷으로 호출 순서만 본다) ---

    @Test
    void 허가를_얻고_결과를_기록하면_반납하지_않는다() {
        CircuitBreaker circuitBreaker = permittingCircuitBreaker(true);
        givenWithdrawSucceeded();

        withdrawWith(circuitBreaker);

        verify(circuitBreaker).onResult(anyLong(), any(), any(BankResult.Succeeded.class));
        verify(circuitBreaker, never()).releasePermission();
    }

    @Test
    void 허가를_얻고_Feign_예외로_결과_모름이면_결과로_기록하고_반납하지_않는다() {
        CircuitBreaker circuitBreaker = permittingCircuitBreaker(true);
        when(feignClient.withdraw(any(), any())).thenThrow(timeout());

        withdrawWith(circuitBreaker);

        verify(circuitBreaker).onResult(anyLong(), any(), any(BankResult.Unknown.class));
        verify(circuitBreaker, never()).releasePermission();
    }

    @Test
    void 허가를_얻었는데_결과를_기록하지_못하고_예외로_끝나면_반납만_하고_다시_던진다() {
        CircuitBreaker circuitBreaker = permittingCircuitBreaker(true);
        IllegalStateException failure = new IllegalStateException("interceptor");
        when(feignClient.withdraw(any(), any())).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> withdrawWith(circuitBreaker)));

        verify(circuitBreaker).releasePermission();
        verify(circuitBreaker, never()).onResult(anyLong(), any(), any());
        verify(circuitBreaker, never()).onError(anyLong(), any(), any());
    }

    @Test
    void 허가를_얻지_못하면_보내지_않고_반납하지_않는다() {
        CircuitBreaker circuitBreaker = permittingCircuitBreaker(false);

        assertEquals(new BankResult.NotSent(BANK_A), withdrawWith(circuitBreaker));

        verify(circuitBreaker, never()).releasePermission();
        verify(circuitBreaker, never()).onResult(anyLong(), any(), any());
        verify(feignClient, never()).withdraw(any(), any());
    }

    // --- Feign 밖 예외: 실제 서킷 ---

    @Test
    void Feign_밖_예외는_5번_나도_서킷에_기록되지_않아_열리지_않는다() {
        when(feignClient.withdraw(any(), any())).thenThrow(new IllegalStateException("interceptor"));

        for (int i = 0; i < 5; i++) {
            assertThrows(IllegalStateException.class, () -> withdraw(BANK_A));
        }

        assertEquals(CircuitBreaker.State.CLOSED, state(BANK_A));
        assertEquals(0, registry.circuitBreaker(BANK_A).getMetrics().getNumberOfBufferedCalls());
    }

    @Test
    void 반열림에서_Feign_밖_예외로_끝나면_자리가_반납되어_다음_시험_호출을_보낼_수_있다() {
        halfOpen(BANK_A);
        when(feignClient.withdraw(any(), any()))
                .thenThrow(new IllegalStateException("interceptor"))
                .thenReturn(ApiResponse.success(succeeded()));

        assertThrows(IllegalStateException.class, () -> withdraw(BANK_A));
        assertEquals(CircuitBreaker.State.HALF_OPEN, state(BANK_A));

        assertInstanceOf(BankResult.Succeeded.class, withdraw(BANK_A));
        assertEquals(CircuitBreaker.State.CLOSED, state(BANK_A));
    }

    // --- 느린 호출 판정 꺼짐 ---

    @Test
    void 결과_모름이_아닌_응답은_5번_연달아_아주_오래_걸려도_열리지_않는다() {
        when(feignClient.withdraw(any(), any())).thenAnswer(invocation -> {
            fakeNanos.addAndGet(Duration.ofHours(1).toNanos());
            return ApiResponse.success(succeeded());
        });

        for (int i = 0; i < 5; i++) {
            assertInstanceOf(BankResult.Succeeded.class, withdraw(BANK_A));
        }

        CircuitBreaker circuitBreaker = registry.circuitBreaker(BANK_A);
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals(5, circuitBreaker.getMetrics().getNumberOfBufferedCalls());
        assertEquals(0, circuitBreaker.getMetrics().getNumberOfSlowCalls());
    }

    @Test
    void 느린_호출_기준_시간은_long_나노초로_넘을_수_없는_값이다() {
        CircuitBreakerConfig config = BankCircuitBreakerConfig.config(BankCircuitBreakerTestSupport.properties());

        assertEquals(Long.MAX_VALUE, config.getSlowCallDurationThreshold().toNanos());
        assertTrue(config.getSlowCallRateThreshold() <= 100);
        assertFalse(TimeUnit.DAYS.toNanos(Long.MAX_VALUE) > config.getSlowCallDurationThreshold().toNanos(),
                "TimeUnit.toNanos는 Long.MAX_VALUE에서 포화한다");
    }

    private CircuitBreaker permittingCircuitBreaker(boolean permitted) {
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(permitted);
        when(circuitBreaker.getTimestampUnit()).thenReturn(TimeUnit.NANOSECONDS);
        return circuitBreaker;
    }

    private BankResult withdrawWith(CircuitBreaker circuitBreaker) {
        CircuitBreakerRegistry fakeRegistry = mock(CircuitBreakerRegistry.class);
        when(fakeRegistry.circuitBreaker(BANK_A)).thenReturn(circuitBreaker);
        return adapter(fakeRegistry).withdraw(REQUEST_ID, BANK_A, "1234567890", "홍길동", 10_000,
                UUID.randomUUID(), 0);
    }

    private BankClientAdapter adapter(CircuitBreakerRegistry circuitBreakerRegistry) {
        return new BankClientAdapter(feignClient, new BankFaultInjection(null, new MockEnvironment()),
                circuitBreakerRegistry);
    }

    private void halfOpen(String bankCode) {
        CircuitBreaker circuitBreaker = registry.circuitBreaker(bankCode);
        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();
    }

    private CircuitBreaker.State state(String bankCode) {
        return registry.circuitBreaker(bankCode).getState();
    }

    private BankResult withdraw(String bankCode) {
        return adapter.withdraw(REQUEST_ID, bankCode, "1234567890", "홍길동", 10_000, UUID.randomUUID(), 0);
    }

    private void givenWithdrawSucceeded() {
        when(feignClient.withdraw(any(), any())).thenAnswer(invocation -> ApiResponse.success(succeeded()));
    }

    private static BankTransactionResponse succeeded() {
        return new BankTransactionResponse(UUID.randomUUID(), REQUEST_ID, "DEBIT", "SUCCEEDED", null);
    }
}
