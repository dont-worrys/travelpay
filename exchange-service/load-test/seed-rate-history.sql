-- 측정 전에 환율 테이블을 "표준 상태"로 맞춥니다. DB 측정과 Redis 측정이 같은 데이터 양에서 시작하게 하려는 것입니다.
-- 앱(mock 모드)을 띄워 Flyway가 테이블을 만든 뒤에 실행합니다. 실행 방법은 README.md를 봅니다.
--
-- 만드는 데이터
--   공식(KOREAEXIM) 환율: 최근 30일, 통화별 하루 1건 → 가짜 환율의 기준값(JPY 838.30, CNY 190.00)
--   가짜(MOCK) 환율 이력: 최근 :hours시간, 5초 간격, 통화별 → 기본 24시간이면 2 × 17,280 = 34,560행
--     마지막 행이 "지금 - 5초"라, 앱의 다음 수집이 이 값에서 이어서 움직입니다.
--     값은 기준값 ±2% 안에서 천천히 오르내리는 모양(사인 곡선)이라 실행할 때마다 같습니다.
-- 측정 결과에 수집기 조회가 섞이지 않게, 마지막에 쿼리 통계(pg_stat_statements)를 비웁니다.

\set ON_ERROR_STOP on
\if :{?hours}
\else
  \set hours 24
\endif

SET search_path TO exchange_service;

BEGIN;

-- 주문·견적이 환율을 참조하므로 함께 비웁니다. (측정 전용 DB에서만 실행)
-- 예약(V7, #240)은 주문을 참조하므로 있으면 함께 비웁니다. 예약 전 커밋의 jar로 잴 때는 테이블이 없어 빼고 비웁니다 (#261)
DO $$
BEGIN
    IF to_regclass('exchange_reservations') IS NOT NULL THEN
        TRUNCATE exchange_reservations, exchange_order_status_histories, exchange_orders, exchange_quotes, exchange_rates;
    ELSE
        TRUNCATE exchange_order_status_histories, exchange_orders, exchange_quotes, exchange_rates;
    END IF;
END
$$;

-- 공식 환율: 오늘 포함 최근 30일. 기준값은 고정, 살 때·팔 때는 ±1%
INSERT INTO exchange_rates (exchange_rate_id, target_currency, unit, mid_rate, buy_rate, sell_rate,
                            source, rate_date, collected_at)
SELECT gen_random_uuid(), c.currency, c.unit, c.base, round(c.base * 1.01, 2), round(c.base * 0.99, 2),
       'KOREAEXIM', d::date, d + interval '2 hours'
FROM (VALUES ('JPY', 100, 838.30), ('CNY', 1, 190.00)) AS c(currency, unit, base)
CROSS JOIN generate_series(date_trunc('day', now()) - interval '29 days', date_trunc('day', now()), interval '1 day') AS d;

-- 가짜 환율 이력: 지금 - 5초부터 거꾸로 5초 간격
INSERT INTO exchange_rates (exchange_rate_id, target_currency, unit, mid_rate, buy_rate, sell_rate,
                            source, rate_date, collected_at)
SELECT gen_random_uuid(), c.currency, c.unit, m.mid, round(m.mid * 1.01, 2), round(m.mid * 0.99, 2),
       'MOCK', (t AT TIME ZONE 'Asia/Seoul')::date, t
FROM (VALUES ('JPY', 100, 838.30), ('CNY', 1, 190.00)) AS c(currency, unit, base)
CROSS JOIN generate_series(1, :hours * 720) AS i
CROSS JOIN LATERAL (SELECT now() - make_interval(secs => 5 * i) AS t) AS ts
-- sin()은 실수(double)를 돌려주므로 numeric으로 바꿔 소수 둘째 자리로 반올림
CROSS JOIN LATERAL (SELECT round(c.base * (1 + 0.015 * sin(i / 500.0)::numeric), 2) AS mid) AS m;

COMMIT;

ANALYZE exchange_rates;
-- 확장은 public 스키마에 있으므로 스키마를 붙여 부른다 (위에서 search_path를 exchange_service로 바꿈)
SELECT public.pg_stat_statements_reset();

SELECT source, target_currency, count(*) AS rows, min(collected_at) AS oldest, max(collected_at) AS latest
FROM exchange_rates
GROUP BY source, target_currency
ORDER BY source, target_currency;
