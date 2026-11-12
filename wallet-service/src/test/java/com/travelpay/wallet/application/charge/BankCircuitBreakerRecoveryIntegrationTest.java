package com.travelpay.wallet.application.charge;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.charge.dto.LedgerPostResult;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.charge.AccountNumberCipher;
import com.travelpay.wallet.domain.charge.BankAccount;
import com.travelpay.wallet.domain.charge.ChargeRecoveryTarget;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import com.travelpay.wallet.infra.client.FeignFailures;
import com.travelpay.wallet.infra.client.bank.BankFeignClient;
import com.travelpay.wallet.infra.client.bank.BankTransactionResponse;
import com.travelpay.wallet.infra.client.bank.BankWithdrawalRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 은행 서킷과 복구 스케줄러를 실제 Postgres와 실제 은행 어댑터·서킷 레지스트리로 검증한다 (Feign 클라이언트만 대역).
 * 은행별 조회 제외, 다른 은행 복구가 밀리지 않음, 반열림 시험 호출, 복구 경로의 NotSent 불변을 본다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "travelpay.wallet.outbox.publisher.enabled=false",
        "travelpay.wallet.charge.recovery.enabled=false",
        "travelpay.wallet.charge.reconciliation.enabled=false",
        "travelpay.wallet.charge.saga.consumer.enabled=false",
        "spring.kafka.admin.auto-create=false"
})
class BankCircuitBreakerRecoveryIntegrationTest {

    private static final String SCHEMA = "wallet_service";
    private static final long AMOUNT = 10_000;
    private static final String BANK_A = "004";
    private static final String BANK_B = "088";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("travelpay.wallet.account-number-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @MockitoBean
    private BankFeignClient feignClient;

    @MockitoBean
    private LedgerTransactionClient ledgerClient;

    /** 실제 은행 어댑터를 감싸 호출 직전 DB 상태를 기록한다 */
    @MockitoSpyBean
    private BankClient bankClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private BankAvailability bankAvailability;

    @Autowired
    private ChargeSagaExecutor executor;

    @Autowired
    private ChargeRecoverySteps recoverySteps;

    @Autowired
    private ChargeTransactionSteps steps;

    @Autowired
    private AccountNumberCipher cipher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ChargeRecoveryScheduler scheduler;
    private UUID userId;
    private UUID walletId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".charge_status_history, " + SCHEMA + ".outbox_events, "
                + SCHEMA + ".charge_requests, " + SCHEMA + ".wallet_currencies, " + SCHEMA + ".wallets CASCADE");
        userId = UUID.randomUUID();
        walletId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO wallets (id, user_id, status) VALUES (?, ?, 'ACTIVE')", walletId, userId);
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        scheduler = new ChargeRecoveryScheduler(recoverySteps, executor, bankAvailability, new SimpleMeterRegistry(),
                10, "10s");
        // bank_tx_id·ledger_ref는 UNIQUE라 호출마다 다른 ID를 준다
        when(ledgerClient.postCharge(any(), any(), any(), anyLong()))
                .thenAnswer(invocation -> new LedgerPostResult.Posted(UUID.randomUUID()));
    }

    @Test
    void 제외_목록의_은행은_조회에서_빠지고_빈_목록이면_조건이_없다() {
        UUID bankA = newCharge(ChargeStatus.BANK_PROCESSING, BANK_A, 3600);
        UUID bankB = newCharge(ChargeStatus.BANK_PROCESSING, BANK_B, 3600);

        assertEquals(Set.of(bankA, bankB), ids(recoverySteps.findTargets(ChargeRecoveryGroup.BANK.statuses(),
                Set.of(), 10)));
        assertEquals(Set.of(bankB), ids(recoverySteps.findTargets(ChargeRecoveryGroup.BANK.statuses(),
                Set.of(BANK_A), 10)));
        assertEquals(Set.of(), ids(recoverySteps.findTargets(ChargeRecoveryGroup.BANK.statuses(),
                Set.of(BANK_A, BANK_B), 10)));
        ChargeRecoveryTarget target = recoverySteps.findTargets(ChargeRecoveryGroup.BANK.statuses(),
                Set.of(BANK_B), 10).getFirst();
        assertEquals(BANK_A, target.bankCode());
    }

    @Test
    void 서킷이_열린_은행은_복구_조회에서_빠지고_다른_은행_복구는_밀리지_않는다() {
        List<UUID> bankA = List.of(newCharge(ChargeStatus.BANK_PROCESSING, BANK_A, 7200),
                newCharge(ChargeStatus.BANK_PROCESSING, BANK_A, 7100),
                newCharge(ChargeStatus.BANK_PROCESSING, BANK_A, 7000));
        List<UUID> bankB = List.of(newCharge(ChargeStatus.BANK_PROCESSING, BANK_B, 3600),
                newCharge(ChargeStatus.BANK_PROCESSING, BANK_B, 3500));
        Map<UUID, Timestamp> updatedBefore = Map.of(bankA.get(0), updatedAt(bankA.get(0)),
                bankA.get(1), updatedAt(bankA.get(1)), bankA.get(2), updatedAt(bankA.get(2)));
        circuitBreakerRegistry.circuitBreaker(BANK_A).transitionToOpenState();
        givenWithdrawSucceeds();

        scheduler.recover();

        for (UUID chargeId : bankB) {
            assertEquals("COMPLETED", status(chargeId));
        }
        for (UUID chargeId : bankA) {
            assertEquals("BANK_PROCESSING", status(chargeId));
            assertEquals(updatedBefore.get(chargeId), updatedAt(chargeId), "선점하지 않아 대기 시간이 그대로");
        }
        verify(feignClient, never()).withdraw(any(), argThat(request -> BANK_A.equals(request.bankCode())));
        verify(feignClient, times(2)).withdraw(any(), argThat(request -> BANK_B.equals(request.bankCode())));
    }

    @Test
    void 반열림_뒤_복구_대상으로_시험_호출이_나가고_성공하면_닫혀_같은_은행의_나머지도_처리한다() {
        UUID first = newCharge(ChargeStatus.BANK_PROCESSING, BANK_A, 7200);
        UUID second = newCharge(ChargeStatus.BANK_PROCESSING, BANK_A, 3600);
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(BANK_A);
        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();
        givenWithdrawSucceeds();

        scheduler.recover();

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals("COMPLETED", status(first));
        assertEquals("COMPLETED", status(second));
    }

    @ParameterizedTest
    @EnumSource(value = ChargeStatus.class, names = {"BANK_PROCESSING", "REQUESTED"})
    void 조회와_선점_사이에_서킷이_열리면_선점만_되고_NotSent로_끝나_백오프가_다시_시작된다(ChargeStatus status) {
        // 반열림 시험 호출(첫 건)이 결과 모름 → 다시 열림. 둘째 건은 조회·제외 목록 계산 뒤라 선점되고 NotSent로 끝난다
        UUID trial = newCharge(ChargeStatus.BANK_PROCESSING, BANK_A, 7200);
        UUID target = newCharge(status, BANK_A, 3600);
        Timestamp queriedUpdatedAt = updatedAt(target);
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(BANK_A);
        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();
        when(feignClient.withdraw(any(), any())).thenThrow(FeignFailures.timeout());
        AtomicReference<Map<String, Object>> beforeCall = new AtomicReference<>();
        doAnswer(invocation -> {
            if (target.equals(invocation.getArgument(5))) {
                beforeCall.set(snapshot(target));
            }
            return invocation.callRealMethod();
        }).when(bankClient).withdraw(anyString(), anyString(), anyString(), anyString(), anyLong(), any(), anyInt());

        scheduler.recover();

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertEquals(1, attemptCount(trial));
        verify(feignClient, times(1)).withdraw(any(), any());
        // 호출 전 쓰기(선점, REQUESTED면 BANK_PROCESSING 전이)는 커밋됐고, NotSent 뒤에는 아무것도 바뀌지 않는다
        Map<String, Object> afterNotSent = snapshot(target);
        assertEquals(beforeCall.get(), afterNotSent, "NotSent 뒤 상태·attempt_count·last_error·이력·updated_at 불변");
        assertEquals("BANK_PROCESSING", afterNotSent.get("status"));
        assertEquals(0, afterNotSent.get("attempt_count"));
        assertNull(afterNotSent.get("last_error"));
        assertNotEquals(queriedUpdatedAt, afterNotSent.get("updated_at"), "선점으로 updated_at이 갱신됨");
        assertTrue(recoverySteps.findTargets(ChargeRecoveryGroup.BANK.statuses(), Set.of(), 10).stream()
                .noneMatch(t -> t.id().equals(target)), "선점 시각부터 대기 시간이 다시 시작되어 바로 조회되지 않는다");
    }

    private void givenWithdrawSucceeds() {
        when(feignClient.withdraw(any(), any())).thenAnswer(invocation -> {
            BankWithdrawalRequest request = invocation.getArgument(1);
            return ApiResponse.success(new BankTransactionResponse(UUID.randomUUID(), request.requestId(), "DEBIT",
                    "SUCCEEDED", null));
        });
    }

    /** 상태를 맞추고 updated_at을 DB now() 기준 secondsAgo초 전으로 둔 충전 */
    private UUID newCharge(ChargeStatus status, String bankCode, int secondsAgo) {
        ChargeRequest charge = steps.accept(ChargeRequest.create(userId, walletId, AMOUNT, Currency.KRW,
                BankAccount.encrypt(bankCode, "1234567890", cipher), "홍길동", UUID.randomUUID()));
        jdbcTemplate.update("""
                UPDATE charge_requests
                   SET status = ?, updated_at = now() - make_interval(secs => ?)
                 WHERE id = ?
                """, status.name(), secondsAgo, charge.getId());
        return charge.getId();
    }

    private static Set<UUID> ids(List<ChargeRecoveryTarget> targets) {
        return Set.copyOf(targets.stream().map(ChargeRecoveryTarget::id).toList());
    }

    /** NotSent 불변 비교용: 상태·attempt_count·last_error·updated_at과 이력 행 수 */
    private Map<String, Object> snapshot(UUID chargeId) {
        return jdbcTemplate.queryForMap("""
                SELECT c.status, c.attempt_count, c.last_error, c.updated_at,
                       (SELECT count(*) FROM charge_status_history h WHERE h.charge_id = c.id) AS history_count
                  FROM charge_requests c WHERE c.id = ?
                """, chargeId);
    }

    private String status(UUID chargeId) {
        return jdbcTemplate.queryForObject("SELECT status FROM charge_requests WHERE id = ?", String.class, chargeId);
    }

    private int attemptCount(UUID chargeId) {
        return jdbcTemplate.queryForObject("SELECT attempt_count FROM charge_requests WHERE id = ?",
                Integer.class, chargeId);
    }

    private Timestamp updatedAt(UUID chargeId) {
        return jdbcTemplate.queryForObject("SELECT updated_at FROM charge_requests WHERE id = ?", Timestamp.class,
                chargeId);
    }
}
