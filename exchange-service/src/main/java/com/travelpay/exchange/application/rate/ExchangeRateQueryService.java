package com.travelpay.exchange.application.rate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import org.springframework.stereotype.Service;

/**
 * 저장된 통화별 최신 환율을 조회합니다. 외부 API는 호출하지 않습니다.
 * 현재 모드의 출처 환율만 조회해, 다른 모드에서 쌓인 데이터(예: 이전 MOCK 실행분)가 섞이지 않게 합니다.
 * 최신 환율 캐시를 먼저 보고, 캐시에 없는 통화만 DB에서 읽습니다(cache-aside 읽기).
 * DB에서 읽은 값을 캐시에 다시 넣지는 않습니다. 캐시에 쓰는 곳은 수집기 하나입니다.
 */
@Service
public class ExchangeRateQueryService {

    private final ExchangeRateRepository repository;
    private final LatestRateCache cache;
    private final CacheFailureLog failureLog;
    private final RateSource activeSource;

    public ExchangeRateQueryService(ExchangeRateRepository repository, ExchangeRateProvider provider,
                                    LatestRateCache cache, CacheFailureLog failureLog) {
        this.repository = repository;
        this.cache = cache;
        this.failureLog = failureLog;
        // Provider에서는 현재 모드의 출처만 확인하고, fetch(외부 호출)는 하지 않습니다.
        this.activeSource = provider.source();
    }

    /**
     * 지정한 통화별 최신 환율 목록입니다(요청한 통화 순서). 아직 수집된 환율이 없는 통화는 목록에서 빠집니다.
     * 캐시에 있는 통화는 캐시에서, 없는 통화만 모아 DB 쿼리 한 번으로 읽습니다.
     * 통화마다 따로 판단하므로 한 통화가 캐시에 없어도 다른 통화는 캐시를 씁니다.
     * DB 쿼리는 하나라 트랜잭션 없이(자동 커밋) 실행해 BEGIN·COMMIT 왕복을 줄입니다.
     */
    public List<ExchangeRate> findLatest(List<ForeignCurrency> currencies) {
        Map<ForeignCurrency, ExchangeRate> cached = readCache(() -> cache.findAll(activeSource, currencies), Map.of());
        List<ForeignCurrency> missing = currencies.stream()
                .filter(currency -> !cached.containsKey(currency))
                .toList();
        Map<ForeignCurrency, ExchangeRate> fromDb = new EnumMap<>(ForeignCurrency.class);
        if (!missing.isEmpty()) {
            for (ExchangeRate rate : repository.findLatestOfEach(activeSource, missing)) {
                fromDb.put(rate.getCurrency(), rate);
            }
        }

        List<ExchangeRate> latest = new ArrayList<>(currencies.size());
        for (ForeignCurrency currency : currencies) {
            ExchangeRate rate = cached.getOrDefault(currency, fromDb.get(currency));
            if (rate != null) {
                latest.add(rate);
            }
        }
        return latest;
    }

    /**
     * 한 통화의 최신 환율입니다. 캐시에 없으면 DB에서 읽고, 아직 수집된 환율이 없으면 empty입니다.
     * 트랜잭션을 걸지 않습니다. 걸면 캐시에서 읽을 때도 DB 연결을 잡고 BEGIN·COMMIT을 보냅니다.
     * 견적 발급처럼 이미 트랜잭션 안에서 부르면 DB 조회는 그 트랜잭션에서 실행됩니다.
     */
    public Optional<ExchangeRate> findLatest(ForeignCurrency currency) {
        return readCache(() -> cache.find(activeSource, currency), Optional.<ExchangeRate>empty())
                .or(() -> repository.findLatest(activeSource, currency));
    }

    /**
     * 캐시를 읽다 실패하면(Redis 꺼짐·응답 없음) 캐시에 없는 것으로 보고 DB에서 읽게 합니다.
     * 캐시는 빠르게 하려는 보조 수단이라, 캐시 장애로 환율 조회·견적 발급이 실패하지 않게 합니다.
     * 연결이 끊긴 동안은 Lettuce가 명령을 바로 거절해(REJECT_COMMANDS) 기다리지 않고, 응답이 없으면 명령 타임아웃(200ms)까지만 기다립니다.
     */
    private <T> T readCache(Supplier<T> read, T whenFailed) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            failureLog.warn("조회", e);
            return whenFailed;
        }
    }
}
