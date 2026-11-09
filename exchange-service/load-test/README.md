# 환전 서비스 부하 측정

환율 조회(`GET /api/v1/exchange-rates`)와 견적 발급(`POST /api/v1/exchange-quotes`)을 같은 조건으로 반복 측정하기 위한 도구입니다.
DB만 쓸 때와 Redis를 쓸 때를 같은 방법으로 재서 비교합니다(#102 환율 조회, #133 견적 발급, Redis 캐싱 이슈).

| 측정 대상 | k6 스크립트 | 보는 것 |
| --- | --- | --- |
| 환율 조회 | `rate-query.js` (기본) | 읽기만 하는 API. 최신 환율 읽기 (v0: SELECT 2번 / v0.5: LATERAL 1번 / v1: Redis MGET 1번) |
| 견적 발급 | `quote-issue.js` | 외화 한 통화의 최신 환율 읽기 + 견적 저장 (quote-db: DB INSERT + 커밋 / quote-redis: Redis SET, DB 연결 안 씀) |

견적 발급은 견적 저장소 전환(#223) 하나의 효과만 봅니다: #223 머지 직전(quote-db, 환율은 Redis·견적은 DB) → #223 머지 커밋(quote-redis, 둘 다 Redis).
환율을 Redis에서 읽는 효과는 환율 조회 측정(v0 → v0.5 → v1)으로 이미 검증했으므로, 견적은 바뀐 변수 하나만 비교합니다.
설계와 측정 결과는 [환율 Redis 캐싱 설계·측정 기록](https://claude.ai/artifact/APBg2irhYAYYukDLjHWSA1)에 남깁니다.

## 구성

| 파일 | 하는 일 |
| --- | --- |
| `compose.yaml` | 측정 전용 Postgres(5439, pg_stat_statements 켬), 캐시 Redis(measure-redis, 팀 redis-cache와 같은 설정), 환전 서비스(app, mock 모드), k6(부하 생성기)를 같은 Docker 네트워크에 띄움 |
| `init/01-extensions.sql` | 측정 DB를 처음 만들 때 pg_stat_statements 확장 생성 |
| `seed-rate-history.sql` | 측정 전에 환율 테이블을 표준 상태로 맞춤 (공식 30일 + 가짜 이력, 쿼리 통계 초기화) |
| `k6-common.js` | 두 k6 스크립트가 함께 쓰는 부분: 예열·측정 한 시나리오 설정, 응답 집계, 결과 표·JSON |
| `rate-query.js` | k6 스크립트: 환율 조회. 오래된 환율 응답도 셈 |
| `quote-issue.js` | k6 스크립트: 견적 발급. 가상 사용자마다 다른 회원 ID로 원화 → 엔화 견적 |
| `measure.sh` | 한 단계 측정: 앱 재시작 → 이력 적재 → k6 실행(그동안 DB·커넥션 지표 1초 간격 기록) → 지표 정리 |
| `measure-all.sh` | 50 / 100 / 300 단계를 3번씩 측정하고 단계별 중앙값 표를 만듦 |
| `results/` | 측정 결과 JSON (커밋하지 않음. 비교에 쓴 값은 측정 기록 문서에 옮겨 적음) |

필요한 것은 Docker와 jar를 빌드할 JDK뿐입니다. 앱·DB·k6 모두 컨테이너로 실행합니다(k6는 `grafana/k6` 이미지).

## 측정 DB 띄우기·끄기

```bash
cd exchange-service/load-test
docker compose up -d measure-postgres   # 띄우기
docker compose --profile app down        # 끄기 (데이터 사라짐)
```

- 공용 DB(5432)와 다른 **5439** 포트를 씁니다.
- 이름 있는 볼륨을 두지 않아, **`down` 후 `up`하면 빈 DB**에서 시작합니다. 단 `docker compose up --force-recreate`로 컨테이너만 다시 만들면 postgres 이미지의 익명 볼륨을 이어 받아 **데이터가 남습니다.** 측정마다 쓰는 데이터(환율·견적·주문)는 적재 SQL이 비우고 다시 넣으므로, 측정끼리는 같은 출발점에서 시작합니다.

## 환전 서비스를 mock 모드로 띄우기

환전 서비스도 측정 DB·k6와 **같은 Docker 네트워크의 컨테이너**(`app`)로 띄웁니다. 저장소 루트에서 빌드한 jar를 그대로 실행합니다.
처음 뜰 때 Flyway가 테이블을 만들고, 5초마다 가짜 환율을 수집합니다.

```bash
cd ../..                                  # exchange-service/load-test에서 저장소 루트로
./gradlew :exchange-service:bootJar        # jar 빌드 (코드를 바꿨다면 다시)
cd exchange-service/load-test
docker compose --profile app up -d --wait app   # 측정 DB도 함께 뜸. 준비(health UP)될 때까지 기다림
```

- PC에서 확인할 때는 `http://localhost:18084/api/v1/exchange-rates`로 요청할 수 있습니다. 측정은 Docker 네트워크 안에서 `app:18084`로 합니다.
- 이미지는 JRE가 아니라 JDK(`eclipse-temurin:21.0.13_9-jdk`)입니다. #211 이전 코드(비교 측정한 v0·v0.5·v1 jar 포함)는 가짜 환율 공급자가 `RandomGenerator.getDefault()`를 써서, `jdk.random` 모듈이 없는 Temurin JRE 이미지에서는 시작하다 실패합니다. 지금 코드는 `SplittableRandom`(java.base)이라 JRE에서도 시작하지만, 이전 jar와 같은 조건으로 재기 위해 JDK를 그대로 씁니다.

측정용으로 붙이는 옵션 (`compose.yaml`의 `app.command`, DB 측정과 Redis 측정 모두 같게):

| 옵션 | 이유 |
| --- | --- |
| `--management.server.port=18085` | Actuator(지표)를 별도 포트로 띄움. 같은 포트면 지표 요청이 부하 요청 뒤에 줄 서서, 바쁠 때 지표를 못 읽음 |
| `--server.tomcat.max-keep-alive-requests=-1` | 톰캣은 기본으로 연결 하나에 요청 100번을 받으면 끊음. 초당 수백 번 새 연결이 생기며 Docker 네트워크에서 가끔 연결 시간 초과(`dial: i/o timeout`)가 남. 측정 중 연결을 계속 재사용하게 함 |

### 측정 전 PC 조건 (같은 날 비교할 측정끼리 반드시 같게)

한 PC에서 k6·앱·DB를 함께 돌리므로, PC 상태만으로도 처리량이 크게 바뀝니다.

1. **충전기를 연결합니다.** 배터리면 전력 제한이 더 강합니다.
2. **Windows 전원 모드를 "최고 성능"으로** 둡니다.
3. **IntelliJ로 띄운 팀 서비스, 빌드, 브라우저 등 무거운 작업을 끕니다.** Docker 안의 CPU 지표에는 PC의 다른 프로그램이 쓰는 CPU가 보이지 않습니다.
4. 측정하는 동안 PC를 쓰지 않습니다.

실제로 겪은 일 (2026-11-04, 견적 발급, 노트북 Intel Core Ultra 7 258V): 위 조건 없이 50/100/300 단계를 연달아 재자, 처음 약 7분(50 단계 3번)만 2,553 TPS였고 그 뒤로는 동시 요청 수와 상관없이 약 1,550 TPS였습니다(50을 다시 재도 1,601). 측정 DB에는 쌓인 것이 없었습니다(크기 37MB, 죽은 행 0). 조건을 맞춘 뒤 같은 50 단계를 6번 연속(약 13분) 재자 2,690~2,868 TPS로 떨어지지 않았고, 그동안 PC CPU 성능(`\Processor Information(_Total)\% Processor Performance`)이 170%(부스트 유지)였습니다. 전원 모드와 백그라운드 서비스를 함께 바꿔, 둘 중 무엇이 주원인인지는 나눠 보지 않았습니다.

### 버전별 jar로 비교 (코드만 다르고 나머지는 같게)

DB만 쓸 때와 Redis를 쓸 때는 **코드만 달라야** 합니다. 그래서 **커밋별로 jar를 빌드해 같은 날 같은 PC 조건에서 jar만 바꿔 끼워** 연달아 잽니다. v1에는 캐시를 끄는 설정(`exchange.rate.cache.enabled=false`)이 있지만 비교에 쓰지 않습니다. 캐시를 끈 v1은 트랜잭션 구조 등이 원래 DB 코드(v0, v0.5)와 달라 비교를 흐릴 수 있기 때문입니다.

캐시 Redis(`measure-redis`)는 Redis를 쓰지 않는 jar를 잴 때도 함께 띄워 둡니다(jar 말고는 환경이 같게). `measure.sh`는 이력 적재 직후 Redis를 비웁니다. 적재 SQL이 환율 표를 비우고 새로 채우므로, 앱이 시작하며 캐시에 넣은 환율은 DB에 없는 행이 되어 다음 수집(최대 5초) 전까지 견적이 외래 키에 막히기 때문입니다. 결과 JSON의 `cacheLookups`(측정 구간의 hit/miss/error, 통화 하나당 1)와 요약 표의 `캐시 hit %`로 캐시로 응답했는지 확인합니다(캐시를 쓰지 않는 jar는 `null` / `-`).

저장소 루트에서 시작합니다. 비교할 커밋은 저장소 옆 폴더(`../travelpay-v0`)에 꺼냅니다.

```bash
# 1. 저장소 루트에서: 비교할 커밋을 옆 폴더에 꺼내 jar 빌드 (예: Redis 캐싱 전 커밋)
git worktree add ../travelpay-v0 <커밋>
(cd ../travelpay-v0 && ./gradlew :exchange-service:bootJar)
./gradlew :exchange-service:bootJar                         # 지금 브랜치 jar (기본값으로 쓰임)

# 2. 측정 폴더에서: jar 경로는 이 폴더(exchange-service/load-test) 기준 상대 경로 또는 절대 경로
cd exchange-service/load-test
J=exchange-service/build/libs/exchange-service-0.0.1-SNAPSHOT.jar
./measure-compare.sh rate v0=../../../travelpay-v0/$J v05=../../../travelpay-v05/$J v1=../../../travelpay-v1/$J
# 버전 하나만 잴 때: APP_JAR=../../../travelpay-v0/$J ./measure-all.sh db-v0

# 3. 끝나면 정리 (측정 폴더 기준 경로)
git worktree remove ../../../travelpay-v0
```

| 비교 | jar | 언제 |
| --- | --- | --- |
| 환율 조회: v0 / v0.5 / v1 | v0 `0865bb5`(쿼리 최적화 전) / v0.5 `c40186c`(LATERAL 쿼리) / v1(환율 Redis 캐싱 후) | 환율 Redis 캐싱 이슈가 끝난 뒤 (셋을 같은 날) |
| 견적 발급: quote-db / quote-redis | #223 머지 커밋의 바로 앞 커밋(`<머지>^1`) / #223 머지 커밋 | #223 머지 후 (둘을 같은 날, 아래 "견적 발급 측정") |

측정 기록에는 jar를 빌드한 **커밋 해시**를 함께 남깁니다.

### 측정 환경을 Docker 안에 두는 이유

처음에는 앱을 PC에서 띄웠습니다. 그러면 앱 → DB, k6 → 앱 요청이 모두 Docker Desktop의 포트 전달(`com.docker.backend.exe`, PC ↔ Docker 경계)을 지납니다. 같은 조건에서 동시 요청 300개의 처리량이 실행마다 250~950 TPS로 흔들려, 원인을 찾으려고 연결 하나로 `SELECT 1`을 수천 번 보내 왕복 시간을 쟀습니다 (2026-11-03, ms).

| 경로 | 부하 없을 때 P50 / P99 | 동시 요청 300개 중 P50 / P99 |
| --- | --- | --- |
| PC → Docker DB (포트 전달) | 0.47 / 1.2~1.8 | 2.7 / 6.8~7.4 |
| Docker 안 → Docker DB | 0.044 / 0.25~0.35 | 0.049 / 1.9 |

- DB가 쿼리를 처리하는 시간은 0.025ms(pg_stat_statements)라, 앱 쪽에서 보이는 시간은 대부분 포트 전달 시간이었습니다. 부하 요청과 DB 요청이 같은 포트 전달에 몰려, 부하 중 6배 느려지고 실행마다 달라졌습니다.
- k6·앱·DB를 모두 Docker 네트워크에 둔 뒤 같은 300단계를 3번 재자 처리량이 5,749~6,059 TPS(±3%)로 일정해졌습니다.
- 그래서 앱을 PC에 띄우고 잰 결과는 앱 성능이 아니라 포트 전달을 잰 것이라 기준값으로 쓰지 않습니다.

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

## 인덱스 확인 (측정 전 한 번)

앱이 최신 환율을 읽는 쿼리(`findFirstBySourceAndCurrencyOrderByRateDateDescCollectedAtDesc`)와 같은 모양을 EXPLAIN해, 이력을 적재한 상태에서 인덱스를 쓰는지 확인합니다. DB만 조회할 때의 기준값이 공정하려면 인덱스를 써야 합니다.

```bash
cd exchange-service/load-test
docker compose exec -T measure-postgres psql -U exchange_app -d travelpay -c "
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM exchange_service.exchange_rates
WHERE source = 'MOCK' AND target_currency = 'JPY'
ORDER BY rate_date DESC, collected_at DESC
LIMIT 1;"
```

- 계획에 **`Index Scan using ix_exchange_rates_latest`**가 있으면 됩니다. `Seq Scan`이나 `Sort`가 보이면 인덱스를 쓰지 않는 것이니 측정하지 않습니다.
- 측정 결과 기록에 이 확인 결과(계획과 실행 시간)도 함께 남깁니다.

## 부하 측정 (k6)

동시 요청 수 단계마다 한 번씩 실행합니다. 같은 가상 사용자들이 예열 + 측정 내내 쉬지 않고 요청하고, 요청을 **시작한 시각**(k6 테스트 경과 시간)이 예열 이후인 요청만 집계합니다. `measure.sh`로 돌리면 **예열 30초 + 측정 2분**입니다(JIT·커넥션 풀·DB 버퍼·캐시가 자리 잡게, 2026-11-09에 15초 + 1분에서 늘림). k6만 직접 돌리면 k6 기본값(15초 + 1분)입니다.

- **`VUS`는 "동시 요청 수"입니다.** 가상 사용자가 쉬는 시간 없이 반복하므로, 서버에는 항상 VUS개의 요청이 들어와 있습니다. 실제 사용자는 조회 사이에 쉬므로 실제 사용자 수로는 훨씬 많습니다. 리틀의 법칙(동시 사용자 ≈ 처리량 × (응답 시간 + 쉬는 시간))으로, 300 TPS에서 쉬는 시간을 10초로 가정하면 약 3,000명 이상입니다.
- **예열과 측정을 한 시나리오로 둔 이유:** 시나리오 두 개로 나누면 k6가 시나리오마다 다른 가상 사용자를 써서, 경계에서 (1) 예열 요청이 측정 구간으로 넘어와 부하가 겹치거나 (2) 새 사용자들이 한꺼번에 연결을 맺어 일부가 거부(`connect: connection refused`)됐습니다. 한 시나리오면 예열 때 맺은 연결을 이어 쓰고 측정 구간 내내 동시 요청 수가 정확히 유지됩니다.
- 테스트를 시작하는 순간에는 모든 가상 사용자가 처음 연결을 맺어서 300개에서 연결 거부가 한두 건 날 수 있습니다. 예열 구간이라 집계에서 빠집니다.

```bash
cd exchange-service/load-test
docker compose run --rm -e VUS=50 -e LABEL=db k6 run --quiet /scripts/rate-query.js         # 환율 조회
docker compose run --rm -e VUS=50 -e LABEL=quote-db k6 run --quiet /scripts/quote-issue.js  # 견적 발급
```

| 환경 변수 | 기본 | 뜻 |
| --- | --- | --- |
| `VUS` | 50 | 동시 요청 수(쉬는 시간 없는 가상 사용자 수). 50 / 100 / 300으로 각각 실행 |
| `LABEL` | run | 결과 파일 이름 구분 (`db`, `redis`) → `results/db-vus50.json` |
| `DURATION` / `WARMUP` | 1m / 15s | 측정·예열 길이. 비교할 측정끼리는 같게 둠 |
| `STALE_AFTER_SECONDS` | 6 | (환율 조회) 수집 시각이 이보다 오래된 환율이 있으면 "오래된 응답"으로 셈 (수집 간격 5초 + 여유 1초). 최신 수집 회차를 제때 반영하는지 보는 **실험용 진단 기준**이며, 서비스 신선도 정책(견적 mock 30초)과 다름 |
| `QUOTE_AMOUNT` | 100000 | (견적 발급) 보내는 원화 금액. 1회 한도 1,000 ~ 2,000,000 안 |

- 성공은 환율 조회 200, 견적 발급 201입니다.
- **환율 조회 응답 검증:** 200이어도 지원 통화(JPY·CNY)가 모두 들어 있지 않으면 "불완전 응답"(`incompleteResponses`)으로 셉니다. 측정 환경은 두 통화를 항상 채워 두므로 빠진 응답은 버그이고, 일을 덜 한 응답이 빨라 보여 버전 비교를 흐리지 않게 하나라도 있으면 그 측정을 무효로 멈춥니다. (서비스 자체는 수집 전 통화를 빼고 200을 주는 게 정상이며 바꾸지 않음)
- 견적 발급은 지갑·원장을 부르지 않아 **`User-Id`와 `User-Role: USER` 헤더**만으로 요청합니다(게이트웨이가 채워 줄 헤더를 직접 넣음. 둘 중 하나라도 없으면 401). 가상 사용자 번호로 고정 UUID(1번 → `00000000-0000-4000-8000-000000000001`)를 만들어, 실행마다 같은 회원들이 각자 견적을 받습니다.
- 결과 JSON의 `okAll`은 예열 포함 성공 수입니다. 견적 발급 검산(저장된 견적 수와 비교)에 씁니다.

출력 예 (2026-11-04, 동시 요청 5개 · 5초 시험 실행):

```
[check-quote] 견적 발급, 동시 요청 5개, 측정 5초 (예열 2s 제외)
  요청 6326건 | 성공 TPS 1265.2 | 실패율 0%
  응답(ms) 평균 3.78 | P50 3.46 | P95 6.41 | P99 8.96 | 최대 24.07
  상태 성공 6326 | 4xx 0 | 5xx 0 | 응답 없음 0
  1초 초과 0
```

### 한 단계 측정: `measure.sh`

앱 재시작 → 이력 적재 → k6 실행 → DB·커넥션 지표 정리를 한 번에 합니다. jar를 먼저 빌드해 둡니다(앱은 스크립트가 띄움).

```bash
cd exchange-service/load-test
./measure.sh db 50                               # 환율 조회 (기본 SCRIPT=rate-query.js), 동시 요청 50개
SCRIPT=quote-issue.js ./measure.sh quote-db 50   # 견적 발급
```

- **측정마다 앱을 새로 띄웁니다**(`docker compose up --force-recreate --wait app`). 앞 측정에서 데워진 JIT·커넥션·메모리 상태가 다음 측정에 넘어가지 않게 해, 측정 순서와 상관없이 같은 출발점에서 시작합니다.
- 이력을 다시 적재하므로 쿼리 통계도 매번 0에서 시작합니다.

결과: `results/db-vus50.json`(k6 응답·처리량) + `results/db-vus50-db.json`(DB·커넥션 지표)

| DB·커넥션 지표 | 출처 | 계산 |
| --- | --- | --- |
| `hikariPending` | Actuator `hikaricp_connections_pending` | 연결을 기다리는 요청 수, 측정 구간 표본의 평균·최대 |
| `hikariActive` | `hikaricp_connections_active` | 사용 중 연결 수(최대 10), 평균·최대 |
| `acquireMsAvg` | `hikaricp_connections_acquire_seconds` | 연결을 얻는 데 걸린 평균 시간 (구간의 합계·횟수 차이). `_max`는 일정 시간 동안의 최대를 들고 있어 앞 실행 값이 남으므로 쓰지 않음 |
| `acquireCount` | `hikaricp_connections_acquire_seconds_count` | 측정 구간에 연결을 얻은 횟수(구간 차이). 캐시가 DB 연결을 얼마나 덜 빌리는지 직접 보여 줌. 수집기(5초마다)가 쓴 연결도 포함 |
| `cpuPercent` | `system_cpu_usage`, `process_cpu_usage` | Docker가 쓰는 CPU 전체(k6·앱·DB 합)와 앱 JVM의 사용률. 평균(전체는 최대도) |
| `dbActiveSessions` | `pg_stat_activity` (state=active) | DB에서 실제로 실행 중인 쿼리 수, 평균·최대 |
| `quoteRows` | `exchange_quotes` 행 수 + Redis `exchange:quote:*` 키 수 | `db`: DB 행, `redis`: Redis 키(견적 저장소가 Redis인 jar), `all`: 둘의 합 = 이번 실행(예열 포함)에서 저장된 견적. 측정 직전 적재 SQL이 견적 테이블을, `FLUSHALL`이 견적 키를 비우므로 그대로 셈(키 수명 10분이라 측정 중에 사라지지 않음). `measure`: DB 행 중 `created_at`이 측정 구간 안(어림값). 환율 조회 측정이면 모두 0 |
| `redis` | 측정 Redis `INFO`·`CONFIG` | 측정이 끝난 시점 사용 메모리(`usedMb`), 최대(`maxMb`), 정책(`policy`). 견적이 Redis에 있으면 발급 수 × 키 크기만큼 늘어남. 이번 측정 동안 Redis가 키를 지웠거나(evicted) 메모리 부족으로 쓰기를 거절했으면(OOM) 무효로 끝남 |
| `rateSelectCalls` | `pg_stat_statements` | 환율 SELECT 호출 수. `measure`: DB 세션과 같은 1초 표본에서 누적 값을 읽어 구한 **측정 구간만의** 차이(수집기 포함). `all`: 적재 후 전체(예열 포함, 검산용). 적재 때 통계를 초기화하므로 예열 호출이 섞이지 않게 구간 차이를 씀 |

- 측정 구간은 k6가 **실제로 테스트를 시작한 시각**(끝난 시각 − k6 결과의 `testRunDurationMs`)에서 예열(`WARMUP_SECONDS`, 기본 30초)을 뺀 측정 시간(`MEASURE_SECONDS`, 기본 2분)입니다. 컨테이너를 띄우는 데 걸린 시간과 상관없이 k6 집계 구간과 같습니다.
- 커넥션·CPU 지표는 **앱 컨테이너 안에서** 1초마다 읽습니다. PC에서 읽으면 포트 전달을 지나 바쁠 때 표본이 빠집니다(60초에 44개 → 안에서 읽으면 약 60개). `samples`에 실제 표본 수가 남습니다.
- 표본이 하나도 없거나, 측정 구간의 처음·끝을 덮지 못하거나(첫 표본이 시작보다 3초 넘게 늦거나 마지막 표본이 끝보다 3초 넘게 빠름), 중간에 3초 넘게 빠지면 실패로 끝납니다. 이때 그 회차의 DB 지표 요약과 k6 결과 JSON을 모두 남기지 않습니다. k6 컨테이너가 늦게 떠 수집기가 먼저 끝나거나 지표 읽기가 중간에 실패할 때 구간 일부만으로 평균을 내지 않게 하려는 것입니다(정상 측정의 표본 간격은 최대 2초, 읽기를 한 번 놓치면 약 4초). 지표 응답이 중간에 끊겨 값이 일부만 온 표본은 버립니다.

### 전체 측정: `measure-all.sh`

같은 환경에서도 실행마다 수치가 조금씩 다릅니다. **단계마다 3번 실행하고 중앙값**을 씁니다(튀는 값 하나에 휘둘리지 않게).

```bash
cd exchange-service/load-test
./measure-all.sh db                               # 환율 조회: 50 / 100 / 300 × 3번 = 9번 측정 (한 번에 약 3분 10초, 전체 약 29분)
SCRIPT=quote-issue.js ./measure-all.sh quote-db   # 견적 발급: 같은 단계·횟수
```

- 요약 표 열: TPS, 회차별 TPS, TPS 편차, P50·P95·P99, 연결 대기, 연결 얻는 시간, 연결 얻은 횟수, 환율 SELECT(측정 구간), DB 활성 세션, 캐시 hit, 스크립트별 열(환율 조회는 오래된 응답 %, 견적 발급은 **견적 저장 / 성공 응답**), 실패율, 실패 건수, CPU
- **TPS 편차** = (회차 중 최대 − 최소) / 중앙값. 10%를 넘으면 ⚠가 붙고, **그 버전·단계만 2회 추가 측정**합니다(결과를 보고 유리한 회차를 고르지 않도록 측정 전에 정한 규칙, 2026-11-09)
- **견적 발급 검산:** 회차마다 저장된 견적 수(`quoteRows.all`)가 예열 포함 성공 응답 수(`okAll`)와 같은지 확인하고, 다르면 멈춥니다. 성공 응답을 받은 만큼 정확히 저장됐는지(응답과 저장이 어긋나지 않는지) 보는 것입니다. 측정 구간 행 수는 시각 경계가 1초 안에서 어긋나 이 검산에 쓰지 않습니다.

- 결과: 회차별 `results/db-r1-vus50.json` … 과 중앙값 표 `results/db-summary.md`(마크다운, 실패율·실패 건수와 회차별 TPS도 함께 적어 흔들림을 확인)
- 한 회차라도 실패한 요청(4xx·5xx·응답 없음)이나 불완전 응답이 있으면 그 자리에서 멈춥니다. 실패가 섞인 측정은 무효로 보고 원인부터 찾습니다(k6 기준값은 측정을 멈추지 않게 항상 통과하도록 두었으므로 여기서 확인). 시작할 때 앞 실행의 요약을 지우고 모든 회차가 성공해야 새 요약을 만들어, 남은 요약을 이번 결과로 오인하지 않게 합니다.
- 단계·횟수는 `STAGES="50 100 300"`, 두 번째 인자(`./measure-all.sh db 5`)로 바꿀 수 있습니다. 비교할 측정끼리는 같게 둡니다.

### 버전 비교 측정: `measure-compare.sh`

버전(jar) 여러 개를 같은 날 비교할 때 씁니다. `measure-all.sh`처럼 버전 하나를 다 재고 다음 버전으로 넘어가면, 노트북이 달아오르며 **뒤에 잰 버전이 불리**해집니다. 그래서 회차마다 **부하 단계 순서와 버전 순서를 돌려** 측정합니다(2026-11-09, 측정 계획 검토에서 반영).

```bash
cd exchange-service/load-test
J=exchange-service/build/libs/exchange-service-0.0.1-SNAPSHOT.jar
./measure-compare.sh rate v0=../../../travelpay-v0/$J v05=../../../travelpay-v05/$J v1=../../../travelpay-v1/$J
START_AT=10 ./measure-compare.sh rate v0=... v05=... v1=...   # 중간에 멈췄으면 진행 기록의 번호부터 이어서
```

버전 3개 · 단계 3개 · 3회면 27번 측정합니다(한 번에 약 3분 10초, 전체 약 1시간 25분. 2026-11-09 실측). 순서는 이렇습니다.

| 회차 | 단계 | 버전 순서 |
| --- | --- | --- |
| 1 | 50 | v0 → v05 → v1 |
| 1 | 100 | v05 → v1 → v0 |
| 1 | 300 | v1 → v0 → v05 |
| 2 | 100 | v1 → v0 → v05 |
| 2 | 300 | v0 → v05 → v1 |
| 2 | 50 | v05 → v1 → v0 |
| 3 | 300 | v05 → v1 → v0 |
| 3 | 50 | v1 → v0 → v05 |
| 3 | 100 | v0 → v05 → v1 |

- 단계 순서는 회차마다 한 칸씩 돌리고, 버전 순서는 같은 단계 안에서 각 버전이 처음·중간·끝에 한 번씩 오게 돌립니다.
- 결과: 회차별 `results/rate-v0-r1-vus50.json`(-db.json) …, 버전별 요약 `results/rate-v0-summary.md`, 진행 기록 `results/rate-progress.log`(시작 시각, jar와 커밋, 측정마다 TPS·P95)
- 측정 하나라도 무효(실패 요청·불완전 응답·표본 누락)면 그 자리에서 멈춥니다. 원인을 고친 뒤 `START_AT`으로 이어서 잽니다.
- **요약 전 검사:** 순서표의 모든 결과가 있고, 추가 측정까지 모든 결과가 유효해야 요약을 만듭니다. `START_AT`으로 이어서 재면 앞 회차는 그 실행에서 검사하지 않으므로, 빠지거나 무효인 결과가 요약에 섞이지 않게 하려는 것입니다.
- **같은 PREFIX로 처음부터** 시작하면 멈춥니다. 요약은 그 PREFIX의 회차 파일을 모두 읽어서, 다른 날 결과가 섞이거나 오래 잰 결과를 덮어쓰지 않게 합니다. 다른 PREFIX를 쓰거나 기존 결과를 옮깁니다.

**편차가 10%를 넘은 조합만 2회 추가하기** (요약 표의 `TPS 편차`에 ⚠):

```bash
# 예: v0의 동시 요청 300이 ⚠ → 그 조합만 4·5회차를 잰다 (다른 조합은 3회 그대로)
APP_JAR=../../../travelpay-v0/$J ./measure.sh rate-v0-r4 300
APP_JAR=../../../travelpay-v0/$J ./measure.sh rate-v0-r5 300
# 측정 없이 검사와 요약만 다시: 추가한 회차까지 검사하고, 그 조합만 5회 중앙값으로 요약
SUMMARY_ONLY=1 ./measure-compare.sh rate v0=../../../travelpay-v0/$J v05=../../../travelpay-v05/$J v1=../../../travelpay-v1/$J
```

요약은 단계마다 **있는 회차를 모두** 쓰므로 추가한 회차는 그 조합에만 들어갑니다. 회차 이름(`r4`, `r5`)은 앞 회차와 겹치지 않게 이어서 붙입니다.
- 측정 DB는 버전끼리 같이 씁니다. 비교할 버전들의 Flyway 마이그레이션이 같은지 먼저 확인합니다(다르면 새 버전이 적용한 마이그레이션을 옛 버전이 몰라 시작하지 못함).

### 견적 발급 측정 (#223 전후)

견적 저장소를 Redis로 옮기면(#223) 견적이 Redis 키로 쌓입니다(초당 발급 수 × 키 수명 10분 × 약 500바이트). 초당 3,000건이면 약 900MB라, 기본 128MB 측정 Redis는 몇 분 안에 찹니다. 그래서 **견적 발급은 측정 Redis를 키우고, 비교하는 두 jar 모두 같은 Redis로** 잽니다.

```bash
# 1. 저장소 루트에서: #223 머지 커밋과 그 바로 앞 커밋으로 jar 빌드 (차이가 #223 하나만 되게)
M=<#223 머지 커밋>
git worktree add ../travelpay-quote-db "$M^1" && (cd ../travelpay-quote-db && ./gradlew :exchange-service:bootJar)
git worktree add ../travelpay-quote-redis "$M" && (cd ../travelpay-quote-redis && ./gradlew :exchange-service:bootJar)

# 2. 측정 폴더에서: 측정 Redis를 2GB·noeviction(운영 redis-cache와 같은 정책)으로. 같은 셸에서 끝까지 잽니다
cd exchange-service/load-test
export MEASURE_REDIS_MAXMEMORY=2gb MEASURE_REDIS_POLICY=noeviction
docker compose up -d --force-recreate --wait measure-redis
J=exchange-service/build/libs/exchange-service-0.0.1-SNAPSHOT.jar
SCRIPT=quote-issue.js ./measure-compare.sh quote quote-db=../../../travelpay-quote-db/$J quote-redis=../../../travelpay-quote-redis/$J
```

- `measure.sh`는 견적 발급을 잴 때 측정 Redis가 1GB보다 작으면 시작하지 않습니다. 값이 다른 셸에서 이어 재면(환경 변수 없음) compose가 Redis를 기본값으로 다시 만들어 막힙니다.
- 회차마다 Redis가 키를 지웠거나(evicted) 쓰기를 거절했으면(OOM) 무효로 멈춥니다. 환율 캐시 쓰기가 거절되면 환율을 DB에서 읽게 되어 측정이 공정하지 않기 때문입니다. HTTP 오류로는 드러나지 않아 Redis 통계로 확인합니다.
- quote-redis는 발급에 DB 연결을 쓰지 않습니다. 그래도 `연결 얻은 횟수`는 0이 아니라 수집기(5초마다)·복구 스케줄러·지표 갱신(30초마다)이 쓴 만큼 남습니다(환율 조회 v1과 비슷한 수준).
- 견적 발급 요약 표에는 저장된 견적 수 / 성공 응답 수와 측정 Redis 사용 메모리(회차 중 최대)가 붙습니다.
- 환율 조회를 다시 잴 때는 `unset MEASURE_REDIS_MAXMEMORY MEASURE_REDIS_POLICY` 후 `docker compose up -d --force-recreate --wait measure-redis`로 기본값(128MB·allkeys-lru)으로 돌립니다.
