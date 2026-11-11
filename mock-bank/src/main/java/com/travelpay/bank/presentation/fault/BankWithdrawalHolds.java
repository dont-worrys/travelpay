package com.travelpay.bank.presentation.fault;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 은행 코드별 출금 붙잡기 상태(메모리 보관, 재기동하면 모두 꺼짐).
 * 켜진 은행의 출금은 처리 전에 delay만큼 기다린 뒤 처리한다. 끄면 기다리던 요청도 바로 풀려 처리된다.
 * mock-bank.fault.enabled=true이고 prod 프로필이 아닐 때만 빈을 만든다.
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "mock-bank.fault", name = "enabled", havingValue = "true", matchIfMissing = false)
public class BankWithdrawalHolds {

    private final Map<String, Hold> holds = new ConcurrentHashMap<>();
    private final Clock clock;

    public BankWithdrawalHolds() {
        this(Clock.systemUTC());
    }

    BankWithdrawalHolds(Clock clock) {
        this.clock = clock;
    }

    /** 켜기. 이미 켜져 있으면 새 대기 시간으로 바꾼다(이미 기다리던 요청은 이전 대기 시간 그대로) */
    public void hold(String bankCode, Duration delay) {
        holds.put(bankCode, new Hold(delay, clock.instant()));
    }

    /** 끄기. 기다리던 요청은 바로 풀려 처리된다. 켜져 있지 않았으면 false */
    public boolean release(String bankCode) {
        Hold removed = holds.remove(bankCode);
        if (removed == null) {
            return false;
        }
        removed.released.countDown();
        return true;
    }

    public boolean isEmpty() {
        return holds.isEmpty();
    }

    public Optional<Hold> find(String bankCode) {
        return Optional.ofNullable(holds.get(bankCode));
    }

    /** 은행 코드 순 현재 상태 */
    public List<HoldStatus> statuses() {
        return holds.entrySet().stream()
                .map(entry -> new HoldStatus(entry.getKey(), entry.getValue().delay.toSeconds(),
                        entry.getValue().since, entry.getValue().heldCount.get()))
                .sorted(Comparator.comparing(HoldStatus::bankCode))
                .toList();
    }

    /**
     * @param heldCount 켠 뒤 붙잡은 출금 요청 수
     */
    public record HoldStatus(String bankCode, long delaySeconds, Instant since, long heldCount) {
    }

    public static final class Hold {
        private final Duration delay;
        private final Instant since;
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicLong heldCount = new AtomicLong();

        private Hold(Duration delay, Instant since) {
            this.delay = delay;
            this.since = since;
        }

        public Duration delay() {
            return delay;
        }

        /** delay만큼 기다린다. 그 전에 끄면 바로 돌아온다 */
        void await() throws InterruptedException {
            heldCount.incrementAndGet();
            released.await(delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
