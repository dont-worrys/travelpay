package com.travelpay.exchange.application.rate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 전일 대비의 비교 기준(전일 환율)을 서버 메모리에 기억합니다(#272, 로컬 캐시).
 *
 * 전일 환율은 최신 고시일이 같으면 바뀌지 않습니다(이미 지난 날의 마지막 값). 그래서 (출처, 통화)마다
 * "최신 고시일 → 전일 환율"을 한 칸씩 기억해 두고, 최신 고시일이 바뀔 때만 DB에서 다시 읽습니다.
 * 환율 조회는 캐시(Redis) hit면 DB를 쓰지 않는데, 전일 값 때문에 요청마다 DB를 부르지 않게 하려는 것입니다.
 * 서버마다 따로 기억하므로 서버 수만큼, 하루에 통화당 한 번 DB를 읽습니다. 재시작하면 다시 읽습니다.
 *
 * - 전일 환율이 없으면(수집을 시작한 첫날) "없음"도 기억합니다. 앞선 날의 환율은 나중에 생기지 않습니다.
 * - DB에서 읽다 실패하면 전일 값 없이 응답하고, RETRY_AFTER 동안은 다시 읽지 않습니다.
 *   DB가 멈췄을 때 요청마다 연결을 기다리며 환율 조회(캐시로는 동작 가능)까지 느려지지 않게 하려는 것입니다.
 * - 같은 칸을 여러 요청이 동시에 읽으려 하면 한 요청만 DB를 읽고 나머지는 그 결과를 씁니다(ConcurrentHashMap.compute).
 *   고시일이 바뀌는 순간 몰린 요청이 같은 쿼리를 한꺼번에 보내지 않게 합니다.
 */
@Slf4j
@Component
public class PreviousRateCache {

    /** 전일 환율을 읽지 못했을 때 다시 읽지 않고 기다리는 시간입니다. */
    static final Duration RETRY_AFTER = Duration.ofSeconds(30);

    private final ExchangeRateRepository repository;
    private final Clock clock;
    private final Map<Slot, Entry> entries = new ConcurrentHashMap<>();

    public PreviousRateCache(ExchangeRateRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 최신 환율의 전일 환율입니다. 같은 최신 고시일이면 기억한 값을 돌려줍니다.
     *
     * @return 전일 환율. 없거나 읽지 못했으면 empty
     */
    public Optional<ExchangeRate> find(ExchangeRate latest) {
        Slot slot = new Slot(latest.getSource(), latest.getCurrency());
        LocalDate rateDate = latest.getRateDate();
        Instant now = clock.instant();
        return entries.compute(slot, (key, current) ->
                current != null && current.isUsable(rateDate, now) ? current : load(key, rateDate, now)).previous();
    }

    private Entry load(Slot slot, LocalDate rateDate, Instant now) {
        try {
            return Entry.loaded(rateDate, repository.findLatestBefore(slot.source(), slot.currency(), rateDate));
        } catch (RuntimeException e) {
            log.warn("전일 환율을 읽지 못해 전일 대비 없이 응답합니다. {}초 뒤 다시 읽습니다. source={}, currency={}, rateDate={}: {}",
                    RETRY_AFTER.toSeconds(), slot.source(), slot.currency(), rateDate, e.toString());
            return Entry.failed(rateDate, now.plus(RETRY_AFTER));
        }
    }

    private record Slot(RateSource source, ForeignCurrency currency) {
    }

    /**
     * 한 칸에 기억한 값입니다.
     *
     * @param rateDate   이 값을 구한 최신 고시일
     * @param previous   전일 환율 (없거나 읽지 못했으면 empty)
     * @param retryAfter 읽지 못했을 때 다시 읽을 수 있는 시각. 읽었으면 null
     */
    private record Entry(LocalDate rateDate, Optional<ExchangeRate> previous, Instant retryAfter) {

        static Entry loaded(LocalDate rateDate, Optional<ExchangeRate> previous) {
            return new Entry(rateDate, previous, null);
        }

        static Entry failed(LocalDate rateDate, Instant retryAfter) {
            return new Entry(rateDate, Optional.empty(), retryAfter);
        }

        boolean isUsable(LocalDate latestRateDate, Instant now) {
            return rateDate.equals(latestRateDate) && (retryAfter == null || now.isBefore(retryAfter));
        }
    }
}
