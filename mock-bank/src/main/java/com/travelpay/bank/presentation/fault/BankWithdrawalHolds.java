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
 * 덮어써도 해제 신호·붙잡은 수·켠 시각은 이어 쓰므로, 끄면 덮어쓰기 전부터 기다리던 요청까지 함께 풀린다.
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

    /**
     * 켜기. 이미 켜져 있으면 새 대기 시간으로 바꾼다(이후 출금부터 적용, 이미 기다리던 요청은 이전 대기 시간 그대로).
     * 바꿔도 이전 붙잡기의 해제 신호를 이어 쓰므로 끄면 이미 기다리던 요청도 풀린다.
     */
    public void hold(String bankCode, Duration delay) {
        holds.compute(bankCode, (code, current) -> current == null
                ? new Hold(delay, clock.instant(), new CountDownLatch(1), new AtomicLong())
                : new Hold(delay, current.since, current.released, current.heldCount));
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
        /** 같은 은행 붙잡기를 덮어써도 공유한다. 끄면 열려 덮어쓰기 전후 대기자가 모두 풀린다 */
        private final CountDownLatch released;
        private final AtomicLong heldCount;

        private Hold(Duration delay, Instant since, CountDownLatch released, AtomicLong heldCount) {
            this.delay = delay;
            this.since = since;
            this.released = released;
            this.heldCount = heldCount;
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
