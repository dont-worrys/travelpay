package com.travelpay.exchange.infrastructure.cache.rate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.travelpay.exchange.application.rate.LatestRateCache;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 최신 환율 캐시의 Redis 구현입니다. 키 하나에 출처·통화별 최신 환율 하나를 JSON 문자열로 둡니다.
 * 키: exchange:rate:latest:{출처}:{통화} (예: exchange:rate:latest:MOCK:JPY)
 * 읽기는 여러 통화면 MGET 한 번, 한 통화면 GET 한 번입니다.
 * 쓰기는 통화별 SET(TTL 포함)을 파이프라인으로 한 번에 보냅니다(왕복 1번).
 * 통화끼리 같은 회차일 필요가 없어 MULTI/EXEC(원자적 실행)는 쓰지 않습니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "exchange.rate.cache.enabled", havingValue = "true", matchIfMissing = true)
public class RedisLatestRateCache implements LatestRateCache {

    static final String KEY_PREFIX = "exchange:rate:latest:";

    /** mock 모드 신선도(30초)와 같습니다. 수집이 멈추면 늦어도 이 시간 뒤에는 캐시가 비어 DB로 조회합니다. */
    static final Duration TTL = Duration.ofSeconds(30);

    /** 캐시 형식이 웹 응답의 JSON 설정 변경에 따라 바뀌지 않도록 전용 매퍼를 씁니다. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 조회한 통화(키) 하나마다 결과를 셉니다. Prometheus: exchange_rate_cache_lookups_total{result="hit"|"miss"|"error"}
     * error는 Redis에 연결하지 못했거나 시간 안에 답이 없어 읽지 못한 경우입니다(호출한 쪽이 DB로 대신 읽음).
     */
    static final String LOOKUPS_METRIC = "exchange.rate.cache.lookups";

    private final StringRedisTemplate redis;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    public RedisLatestRateCache(StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.hits = lookups(meterRegistry, "hit");
        this.misses = lookups(meterRegistry, "miss");
        this.errors = lookups(meterRegistry, "error");
    }

    private static Counter lookups(MeterRegistry meterRegistry, String result) {
        return Counter.builder(LOOKUPS_METRIC)
                .description("최신 환율 캐시 조회 수 (통화 하나당 1)")
                .tag("result", result)
                .register(meterRegistry);
    }

    @Override
    public Map<ForeignCurrency, ExchangeRate> findAll(RateSource source, List<ForeignCurrency> currencies) {
        Map<ForeignCurrency, ExchangeRate> found = new EnumMap<>(ForeignCurrency.class);
        if (currencies.isEmpty()) {
            return found;
        }
        List<String> keys = currencies.stream().map(currency -> key(source, currency)).toList();
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(keys);
        } catch (RuntimeException e) {
            errors.increment(keys.size());
            throw e;
        }
        if (values == null) {
            // 파이프라인·트랜잭션 안에서만 null이 오며, 이 클래스는 그렇게 호출하지 않습니다.
            return found;
        }
        for (int i = 0; i < values.size(); i++) {
            ForeignCurrency currency = currencies.get(i);
            lookup(keys.get(i), values.get(i)).ifPresent(rate -> found.put(currency, rate));
        }
        return found;
    }

    @Override
    public Optional<ExchangeRate> find(RateSource source, ForeignCurrency currency) {
        String key = key(source, currency);
        String value;
        try {
            value = redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            errors.increment();
            throw e;
        }
        return lookup(key, value);
    }

    /** 통화가 수천 개로 늘면 한 번에 보내는 양을 나눠야 하지만, 지원 통화는 많아야 수십 개라 한 번에 보냅니다. */
    @Override
    public void putAll(List<ExchangeRate> rates) {
        if (rates.isEmpty()) {
            return;
        }
        List<byte[]> keys = rates.stream()
                .map(rate -> bytes(key(rate.getSource(), rate.getCurrency()))).toList();
        List<byte[]> values = rates.stream()
                .map(rate -> bytes(JSON.writeValueAsString(CachedExchangeRate.from(rate)))).toList();
        redis.executePipelined((RedisCallback<Object>) connection -> {
            for (int i = 0; i < keys.size(); i++) {
                connection.stringCommands().set(keys.get(i), values.get(i), Expiration.from(TTL), SetOption.upsert());
            }
            // 파이프라인 결과는 executePipelined가 모아 돌려주므로 여기서는 null을 반환해야 합니다.
            return null;
        });
    }

    static String key(RateSource source, ForeignCurrency currency) {
        return KEY_PREFIX + source.name() + ":" + currency.name();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 읽은 값을 환율로 바꾸고 hit/miss를 셉니다. 읽을 수 없는 값도 miss입니다(DB에서 읽게 되므로). */
    private Optional<ExchangeRate> lookup(String key, String value) {
        Optional<ExchangeRate> rate = read(key, value);
        (rate.isPresent() ? hits : misses).increment();
        return rate;
    }

    /** 값이 없거나 읽을 수 없는 형식(배포 중 형식이 바뀐 경우 등)이면 캐시에 없는 것으로 봅니다. */
    private static Optional<ExchangeRate> read(String key, String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(value, CachedExchangeRate.class).toExchangeRate());
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            log.warn("환율 캐시 값을 읽지 못해 없는 것으로 봅니다: key={}, reason={}", key, e.getMessage());
            return Optional.empty();
        }
    }
}
