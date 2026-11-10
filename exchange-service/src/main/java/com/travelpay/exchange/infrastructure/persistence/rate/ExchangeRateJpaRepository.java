package com.travelpay.exchange.infrastructure.persistence.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ExchangeRateJpaRepository extends JpaRepository<ExchangeRate, UUID> {

    Optional<ExchangeRate> findFirstBySourceAndCurrencyOrderByRateDateDescCollectedAtDesc(
            RateSource source, ForeignCurrency currency);

    /**
     * 통화마다 위 메서드와 같은 최신 1건을 쿼리 하나로 고르는 SQL입니다 (LATERAL).
     * 통화별로 ix_exchange_rates_latest의 맨 앞 한 줄만 읽습니다. DISTINCT ON은 통화별 이력을 전부 읽어 쓰지 않습니다.
     * 통화 목록은 쉼표로 이은 문자열로 받아 배열로 풀고, WITH ORDINALITY로 요청한 순서를 지킵니다.
     * 풀어낸 통화는 컬럼과 같은 CHAR(3)로 바꿔 비교합니다. text 그대로 비교하면 컬럼 쪽이 text로 변환되어
     * 인덱스를 못 타고 통화마다 이력 전체를 읽고 정렬합니다(EXPLAIN: Seq Scan + Sort, 34,560행에서 860페이지·12ms).
     * 실행 계획은 ExchangeRateLatestQueryIntegrationTest가 확인합니다.
     */
    String LATEST_OF_EACH_SQL = """
            SELECT r.*
            FROM unnest(CAST(string_to_array(:currencies, ',') AS char(3)[])) WITH ORDINALITY AS c(currency, position)
            CROSS JOIN LATERAL (
                SELECT *
                FROM exchange_rates
                WHERE source = :source AND target_currency = c.currency
                ORDER BY rate_date DESC, collected_at DESC
                LIMIT 1
            ) r
            ORDER BY c.position
            """;

    /** LATEST_OF_EACH_SQL을 실행합니다. 쿼리 하나라 한 스냅샷에서 읽으므로 통화들이 같은 수집 회차입니다. */
    @Query(nativeQuery = true, value = LATEST_OF_EACH_SQL)
    List<ExchangeRate> findLatestOfEach(@Param("source") String source, @Param("currencies") String currencies);

    boolean existsBySourceAndCurrencyAndRateDate(RateSource source, ForeignCurrency currency, LocalDate rateDate);
}
