package com.travelpay.exchange.infrastructure.persistence.quote;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.ExchangeQuoteErrorCode;
import com.travelpay.exchange.domain.quote.ExchangeQuoteRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 견적을 Redis(redis-cache 인스턴스)에 저장합니다. 견적은 5분짜리라 DB 대신 Redis에 두어, 발급할 때 DB 연결을 쓰지 않습니다.
 *
 * - 키: exchange:quote:{견적 ID}, 값: 견적 전체 JSON (StoredQuote). 발급은 SET 한 번, 조회는 GET 한 번입니다.
 * - 키 수명: 만료 시각 + 5분. 만료 직후에도 잠시 남겨 두어 "만료됨"(신청 409, 조회 EXPIRED)으로 답하고, 그 뒤에는 사라져 404입니다.
 * - 환율 캐시와 달리 견적은 Redis에만 있는 원본입니다. 그래서 이 인스턴스는 메모리가 차도 키를 지우지 않게(noeviction) 둡니다.
 *
 * 다른 회원의 견적은 없는 것과 같게 다룹니다. 키에 회원 ID를 넣지 않고 값의 회원 ID와 비교합니다.
 */
@Slf4j
@Repository
class RedisExchangeQuoteRepository implements ExchangeQuoteRepository {

    static final String KEY_PREFIX = "exchange:quote:";

    /** 만료 뒤에도 키를 남겨 두는 시간입니다. */
    static final Duration RETENTION_AFTER_EXPIRY = Duration.ofMinutes(5);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StringRedisTemplate redis;

    RedisExchangeQuoteRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 키 수명은 발급 시각부터 "만료 시각 + 남겨 두는 시간"까지입니다. 발급 직후 저장하므로 실제 수명도 거의 같습니다.
     * 서비스 시계와 Redis 시계가 달라도 상관없도록, 절대 시각(EXAT)이 아니라 남은 시간(PX)으로 정합니다.
     */
    @Override
    public ExchangeQuote save(ExchangeQuote quote) {
        Duration ttl = Duration.between(quote.getIssuedAt(), quote.getExpiresAt().plus(RETENTION_AFTER_EXPIRY));
        String value = JSON.writeValueAsString(StoredQuote.from(quote));
        try {
            redis.opsForValue().set(key(quote.getId()), value, ttl);
        } catch (DataAccessException e) {
            throw unavailable("저장", quote.getId(), e);
        }
        return quote;
    }

    @Override
    public Optional<ExchangeQuote> findByIdAndUserId(UUID quoteId, UUID userId) {
        String value;
        try {
            value = redis.opsForValue().get(key(quoteId));
        } catch (DataAccessException e) {
            throw unavailable("조회", quoteId, e);
        }
        if (value == null) {
            return Optional.empty();
        }
        ExchangeQuote quote = JSON.readValue(value, StoredQuote.class).toQuote();
        return quote.getUserId().equals(userId) ? Optional.of(quote) : Optional.empty();
    }

    /**
     * Redis에 닿지 못하면(연결 실패·타임아웃·메모리 가득 등) 503으로 알립니다.
     * 환율 캐시처럼 DB로 대신할 수 없어서 요청을 실패시키고, 원인은 경고 로그로 남깁니다.
     */
    private static BusinessException unavailable(String action, UUID quoteId, DataAccessException cause) {
        log.warn("견적 저장소 {} 실패: quoteId={}, cause={}", action, quoteId, cause.toString());
        return new BusinessException(ExchangeQuoteErrorCode.QUOTE_STORE_UNAVAILABLE, cause);
    }

    static String key(UUID quoteId) {
        return KEY_PREFIX + quoteId;
    }
}
