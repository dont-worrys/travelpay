# 환율 조회 부하 측정

환율 조회(`GET /api/v1/exchange-rates`)를 같은 조건으로 반복 측정하기 위한 도구입니다.
DB만 조회할 때와 Redis 캐시를 쓸 때를 같은 방법으로 재서 비교합니다(#102, Redis 캐싱 이슈).
설계와 측정 결과는 [환율 Redis 캐싱 설계·측정 기록](https://claude.ai/artifact/APBg2irhYAYYukDLjHWSA1)에 남깁니다.

## 구성

| 파일 | 하는 일 |
| --- | --- |
| `compose.yaml` | 측정 전용 Postgres(5439, pg_stat_statements 켬)와 k6(부하 생성기)를 Docker로 띄움 |
| `init/01-extensions.sql` | 측정 DB를 처음 만들 때 pg_stat_statements 확장 생성 |
| `seed-rate-history.sql` | 측정 전에 환율 테이블을 표준 상태로 맞춤 (공식 30일 + 가짜 이력, 쿼리 통계 초기화) |
| `rate-query.js` | k6 스크립트. 예열 후 측정 구간만 집계해 화면에 표로, `results/`에 JSON으로 남김 |
| `results/` | 측정 결과 JSON (커밋하지 않음. 비교에 쓴 값은 측정 기록 문서에 옮겨 적음) |

필요한 것은 Docker뿐입니다. k6는 설치하지 않고 `grafana/k6` 이미지로 실행합니다.

## 측정 DB 띄우기·끄기

```bash
cd exchange-service/load-test
docker compose up -d measure-postgres   # 띄우기 (빈 DB)
docker compose down                      # 끄기 (데이터 사라짐)
```

- 공용 DB(5432)와 다른 **5439** 포트를 씁니다.
- 데이터를 남기지 않아 다시 띄우면 항상 빈 DB에서 시작합니다. 측정마다 같은 출발점에서 시작하려는 것입니다.

## 환전 서비스를 mock 모드로 띄우기

측정 DB에 연결해 띄웁니다. 처음 뜰 때 Flyway가 테이블을 만들고, 5초마다 가짜 환율을 수집합니다.

```bash
./gradlew :exchange-service:bootJar
EXCHANGE_DB_URL="jdbc:postgresql://127.0.0.1:5439/travelpay?currentSchema=exchange_service" \
EXCHANGE_DB_USERNAME=exchange_app EXCHANGE_DB_PASSWORD=load-test-only \
EXCHANGE_RATE_MODE=mock \
java -jar exchange-service/build/libs/exchange-service-0.0.1-SNAPSHOT.jar \
  --eureka.client.enabled=false --server.port=18084
```

## 환율 이력 적재 (측정 직전마다)

앱이 뜬 뒤 실행합니다. 환율·견적·주문 테이블을 비우고 아래 데이터를 넣은 뒤, 쿼리 통계를 초기화합니다.

| 데이터 | 양 (기본) |
| --- | --- |
| 공식(KOREAEXIM) 환율 | 최근 30일 × 2통화 = 60행. 가짜 환율의 기준값 JPY 838.30, CNY 190.00 |
| 가짜(MOCK) 환율 이력 | 최근 24시간 × 5초 간격 × 2통화 = 34,560행. 마지막 행이 "지금 - 5초"라 앱이 그 값에서 이어서 수집 |

```bash
cd exchange-service/load-test
docker compose exec -T measure-postgres psql -U exchange_app -d travelpay -v hours=24 -f /load-test/seed-rate-history.sql
```

- 이력 양은 `-v hours=24`로 바꿀 수 있습니다. **DB 측정과 Redis 측정은 같은 값으로** 실행합니다.
- 가짜 이력 값은 사인 곡선이라 실행할 때마다 같습니다.
- Windows Git Bash에서는 `/load-test/...` 경로가 바뀌어 실패하므로 앞에 `MSYS_NO_PATHCONV=1`을 붙입니다.
- 한 트랜잭션이라 중간에 실패하면 아무것도 바뀌지 않습니다.

## 부하 측정 (k6)

사용자 수 단계마다 한 번씩 실행합니다. 각 실행은 예열 15초 동안 요청만 보내고 집계에서 뺀 뒤, 측정 1분만 집계합니다.

```bash
cd exchange-service/load-test
docker compose run --rm -e VUS=50 -e LABEL=db k6 run --quiet /scripts/rate-query.js
```

| 환경 변수 | 기본 | 뜻 |
| --- | --- | --- |
| `VUS` | 50 | 동시 사용자 수. 50 / 100 / 300으로 각각 실행 |
| `LABEL` | run | 결과 파일 이름 구분 (`db`, `redis`) → `results/db-vus50.json` |
| `DURATION` / `WARMUP` | 1m / 15s | 측정·예열 길이. 비교할 측정끼리는 같게 둠 |
| `STALE_AFTER_SECONDS` | 6 | 수집 시각이 이보다 오래된 환율이 있으면 "오래된 응답"으로 셈 (수집 간격 5초 + 여유 1초) |

출력 예 (2026-11-02, 동시 사용자 5명 · 10초 시험 실행):

```
[trial] 동시 사용자 5명, 측정 10초 (예열 5s 제외)
  요청 4986건 | 성공 TPS 498.6 | 실패율 0%
  응답(ms) 평균 9.8 | P50 8.39 | P95 18.23 | P99 27.65 | 최대 67.74
  상태 200 4986 | 4xx 0 | 5xx 0 | 응답 없음 0
  1초 초과 0 | 오래된 환율 응답 0
```

### 단계마다 순서

같은 조건에서 시작하도록 단계마다 이력을 다시 적재합니다(쿼리 통계도 0으로 초기화됨).

1. 환율 이력 적재 (`seed-rate-history.sql`)
2. k6 실행 (`VUS=50`)
3. 측정 직후 DB·커넥션 지표 기록 (아래 4단계에서 정리)
4. `VUS=100`, `VUS=300`으로 1~3 반복
