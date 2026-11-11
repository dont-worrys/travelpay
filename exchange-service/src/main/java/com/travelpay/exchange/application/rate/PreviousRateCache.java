package com.travelpay.exchange.application.rate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 전일 대비의 비교 기준(전일 환율)을 서버 메모리에 기억합니다(#272, 로컬 캐시).
 *
 * 전일 환율은 최신 고시일이 같으면 바뀌지 않습니다(이미 지난 날의 마지막 값). 그래서 (출처, 통화)마다
 * "최신 고시일 → 전일 환율"을 한 칸씩 기억해 두고, 최신 고시일이 바뀔 때만 DB에서 다시 읽습니다.
 * 환율 조회는 캐시(Redis) hit면 DB를 쓰지 않는데, 전일 값 때문에 요청마다 DB를 부르지 않게 하려는 것입니다.
 * 서버마다 따로 기억하므로 서버 수만큼, 하루에 통화당 한 번 DB를 읽습니다. 재시작하면 다시 읽습니다.
 *
 * - DB 읽기는 요청 스레드가 아니라 별도 스레드(가상 스레드)가 합니다. 칸에는 "읽는 중"(CompletableFuture)만 꽂고,
 *   같은 칸에 몰린 요청은 그 하나의 결과를 함께 기다립니다. 칸을 잠근 채(ConcurrentHashMap.compute 안에서) DB를 읽지 않습니다.
 * - 요청은 결과를 WAIT까지만 기다리고, 넘으면 전일 대비 없이 응답합니다. DB 읽기는 뒤에서 이어져 다음 요청부터 값이 나옵니다.
 *   DB가 느리거나 멈춰도, Redis로 응답할 수 있는 환율 조회가 DB를 기다리며 멈추지 않게 하려는 것입니다(#272 리뷰).
 * - 전일 환율이 없으면(수집을 시작한 첫날) "없음"도 기억합니다. 앞선 날의 환율은 나중에 생기지 않습니다.
 * - DB에서 읽다 실패하면 RETRY_AFTER 동안은 다시 읽지 않습니다.
 */
@Slf4j
@Component
public class PreviousRateCache {

    /** 요청이 전일 환율을 기다리는 최대 시간입니다. Redis 캐시를 기다리는 한도(spring.data.redis.timeout)와 같습니다. */
    static final Duration WAIT = Duration.ofMillis(200);

    /** 전일 환율을 읽지 못했을 때 다시 읽지 않고 기다리는 시간입니다. */
    static final Duration RETRY_AFTER = Duration.ofSeconds(30);

    private final ExchangeRateRepository repository;
    private final Clock clock;
    private final Duration wait;
    private final ExecutorService loader = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Slot, Entry> entries = new ConcurrentHashMap<>();

    @Autowired
    public PreviousRateCache(ExchangeRateRepository repository, Clock clock) {
        this(repository, clock, WAIT);
    }

    /** 테스트용: 기다리는 시간을 바꿉니다. */
    PreviousRateCache(ExchangeRateRepository repository, Clock clock, Duration wait) {
        this.repository = repository;
        this.clock = clock;
        this.wait = wait;
    }

    /**
     * 최신 환율의 전일 환율입니다. 같은 최신 고시일이면 기억한 값을 돌려줍니다.
     *
     * @return 전일 환율. 없거나, 읽지 못했거나, WAIT 안에 읽지 못했으면 empty
     */
    public Optional<ExchangeRate> find(ExchangeRate latest) {
        Slot slot = new Slot(latest.getSource(), latest.getCurrency());
        LocalDate rateDate = latest.getRateDate();
        Instant now = clock.instant();
        // 칸에는 "읽는 중"만 꽂고 바로 빠진다. 새로 꽂힌 경우에만 별도 스레드에서 DB를 읽기 시작한다
        Entry candidate = new Entry(rateDate, new CompletableFuture<>());
        Entry entry = entries.compute(slot, (key, current) ->
                current != null && current.isUsable(rateDate, now) ? current : candidate);
        if (entry == candidate) {
            loader.execute(() -> candidate.result().complete(load(slot, rateDate)));
        }
        return await(entry, slot);
    }

    private Optional<ExchangeRate> await(Entry entry, Slot slot) {
        try {
            return entry.result().get(wait.toMillis(), TimeUnit.MILLISECONDS).previous();
        } catch (TimeoutException e) {
            log.debug("전일 환율을 {}ms 안에 읽지 못해 전일 대비 없이 응답합니다. source={}, currency={}",
                    wait.toMillis(), slot.source(), slot.currency());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            // load가 예외를 결과로 바꾸므로 오지 않는다. 와도 전일 대비 없이 응답한다
            return Optional.empty();
        }
    }

    private Loaded load(Slot slot, LocalDate rateDate) {
        try {
            return Loaded.found(repository.findLatestBefore(slot.source(), slot.currency(), rateDate));
        } catch (RuntimeException e) {
            log.warn("전일 환율을 읽지 못해 전일 대비 없이 응답합니다. {}초 뒤 다시 읽습니다. source={}, currency={}, rateDate={}: {}",
                    RETRY_AFTER.toSeconds(), slot.source(), slot.currency(), rateDate, e.toString());
            return Loaded.failed(clock.instant().plus(RETRY_AFTER));
        }
    }

    @PreDestroy
    void shutdown() {
        loader.shutdownNow();
    }

    private record Slot(RateSource source, ForeignCurrency currency) {
    }

    /**
     * 한 칸에 기억한 값입니다.
     *
     * @param rateDate 이 값을 구하는 최신 고시일
     * @param result   DB에서 읽은 결과. 읽는 중이면 아직 끝나지 않은 상태
     */
    private record Entry(LocalDate rateDate, CompletableFuture<Loaded> result) {

        /** 같은 최신 고시일이고, 읽는 중이거나 읽었거나, 실패했어도 다시 읽을 시각 전이면 그대로 씁니다. */
        boolean isUsable(LocalDate latestRateDate, Instant now) {
            if (!rateDate.equals(latestRateDate)) {
                return false;
            }
            Loaded loaded = result.getNow(null);
            return loaded == null || loaded.retryAfter() == null || now.isBefore(loaded.retryAfter());
        }
    }

    /**
     * DB에서 읽은 결과입니다.
     *
     * @param previous   전일 환율 (없거나 읽지 못했으면 empty)
     * @param retryAfter 읽지 못했을 때 다시 읽을 수 있는 시각. 읽었으면 null
     */
    private record Loaded(Optional<ExchangeRate> previous, Instant retryAfter) {

        static Loaded found(Optional<ExchangeRate> previous) {
            return new Loaded(previous, null);
        }

        static Loaded failed(Instant retryAfter) {
            return new Loaded(Optional.empty(), retryAfter);
        }
    }
}
