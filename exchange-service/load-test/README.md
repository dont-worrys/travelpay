# 환전 서비스 부하 측정

환율 조회(`GET /api/v1/exchange-rates`)와 견적 발급(`POST /api/v1/exchange-quotes`)을 같은 조건으로 반복 측정하기 위한 도구입니다.
DB만 쓸 때와 Redis를 쓸 때를 같은 방법으로 재서 비교합니다(#102 환율 조회, #133 견적 발급, Redis 캐싱 이슈).

| 측정 대상 | k6 스크립트 | 보는 것 |
| --- | --- | --- |
| 환율 조회 | `rate-query.js` (기본) | 읽기만 하는 API. 최신 환율 읽기 (v0: SELECT 2번 / v0.5: LATERAL 1번 / v1: Redis MGET 1번) |
| 견적 발급 | `quote-issue.js` | 외화 한 통화의 최신 환율 읽기 + 견적 저장 (quote-db: DB INSERT + 커밋 / quote-redis: Redis SET, DB 연결 안 씀) |
| 원장 장애 영향 (#282) | `exchange-order.js`, `quote-freshness.js` | 가짜 원장을 느리게 해 환전 신청이 요청 스레드를 붙잡는지, 예약 체결이 환율 수집을 늦추는지 (아래 "원장 장애 측정") |

견적 발급은 견적 저장소 전환(#223) 하나의 효과만 봅니다: #223 머지 직전(quote-db, 환율은 Redis·견적은 DB) → #223 머지 커밋(quote-redis, 둘 다 Redis).
환율을 Redis에서 읽는 효과는 환율 조회 측정(v0 → v0.5 → v1)으로 이미 검증했으므로, 견적은 바뀐 변수 하나만 비교합니다.
설계와 측정 결과는 [환율 Redis 캐싱 설계·측정 기록](https://claude.ai/artifact/APBg2irhYAYYukDLjHWSA1)에 남깁니다.

## 구성

| 파일 | 하는 일 |
| --- | --- |
| `compose.yaml` | 측정 전용 Postgres(5439, pg_stat_statements 켬), 캐시 Redis(measure-redis, 팀 redis-cache와 같은 설정), 환전 서비스(app, mock 모드), k6(부하 생성기)를 같은 Docker 네트워크에 띄움. 원장 장애 측정 때만 가짜 지갑·원장(ledger-stub, `stub` 프로필)도 띄움 |
| `wiremock/mappings/` | 가짜 지갑·원장(WireMock) 응답: 지갑 잔액은 고정 지갑 하나, 원장 거래 등록은 항상 POSTED(지연은 `measure-ledger.sh`가 바꿈) |
| `init/01-extensions.sql` | 측정 DB를 처음 만들 때 pg_stat_statements 확장 생성 |
| `seed-rate-history.sql` | 측정 전에 환율 테이블을 표준 상태로 맞춤 (공식 30일 + 가짜 이력, 쿼리 통계 초기화) |
| `k6-common.js` | 두 k6 스크립트가 함께 쓰는 부분: 예열·측정 한 시나리오 설정, 응답 집계, 결과 표·JSON |
| `rate-query.js` | k6 스크립트: 환율 조회. 오래된 환율 응답도 셈 |
| `quote-issue.js` | k6 스크립트: 견적 발급. 가상 사용자마다 다른 회원 ID로 원화 → 엔화 견적 |
| `measure.sh` | 한 단계 측정: 앱 재시작 → 이력 적재 → k6 실행(그동안 DB·커넥션 지표 1초 간격 기록) → 지표 정리 |
| `measure-all.sh` | 50 / 100 / 300 단계를 3번씩 측정하고 단계별 중앙값 표를 만듦 |
| `measure-compare.sh` | 버전(jar) 여러 개를 회차·단계·버전 순서를 섞어 같은 날 비교 측정 |
| `measure-common.sh` | 위 두 스크립트가 함께 쓰는 결과 검사(실패 요청, 견적 검산)와 요약 표 |
| `exchange-order.js` | k6 스크립트(열린 모델): 환전 신청(견적 → 신청) 초당 60건 + 환율 조회 초당 100건을 함께 보냄 |
| `quote-freshness.js` | k6 스크립트(열린 모델): 견적 발급 초당 10건. 환율이 오래돼 503(`EXCHANGE_RATE_UNAVAILABLE`)이 난 수와 시각을 셈 |
| `measure-ledger.sh` | 원장 장애 한 회차: 가짜 원장 지연 설정 → 앱 재시작 → 이력 적재 → (예약 넣기) → k6 실행(그동안 요청 스레드·커넥션 풀 1초 기록) → 주문·예약 상태 집계 |
| `measure-ledger-compare.sh` | 원장 정상·느림·시간 초과를 회차마다 순서를 돌려 3번씩(9회) 측정 |
| `compose.monitoring.k6-rw.yaml` | 캡처용: 공용 Prometheus가 k6 결과(remote write)를 받게 하는 실행 옵션 (`K6_PROMETHEUS_RW=1`과 함께) |
| `pc-state.sh` | 측정하는 PC의 전원 모드·충전기·팀 서비스 포트·IntelliJ 상태를 JSON 한 줄로 (Windows) |
| `standby-events.sh` | 두 시각 사이 Windows 대기 모드 진입 횟수 (Windows 시스템 로그) |
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
| `--management.metrics.distribution.percentiles-histogram.http.server.requests=${MEASURE_HISTOGRAM:-false}` | HTTP 응답 시간을 구간별 개수(히스토그램)로도 내보내 Grafana가 p95·p99를 계산하게 함. 비교하는 옛 jar에는 이 설정이 없어 실행 옵션으로 정함. **처리량을 낮추므로 기본은 끔**(변수를 빠뜨려도 공식 측정 조건). Grafana 캡처용 측정만 `MEASURE_HISTOGRAM=true`로 켬(아래 "모니터링 연결") |

### 측정 전 PC 조건 (같은 날 비교할 측정끼리 반드시 같게)

한 PC에서 k6·앱·DB를 함께 돌리므로, PC 상태만으로도 처리량이 크게 바뀝니다.

1. **충전기를 연결합니다.** 배터리면 전력 제한이 더 강합니다.
2. **Windows 전원 모드를 비교할 측정끼리 같게** 둡니다(설정 → 시스템 → 전원 및 배터리 → 전원 모드). 다른 날 결과와 비교하려면 그날의 모드와 같아야 합니다.
3. **충전기 연결 시 "화면 끄기"와 "절전"을 "안 함"으로** 둡니다(설정 → 전원 및 배터리 → 화면, 절전 및 최대 절전 시간 제한). 측정이 끝나면 원래대로 돌립니다.
4. **IntelliJ로 띄운 팀 서비스, 빌드, 회의 프로그램, 무거운 브라우저 탭을 끕니다.** Docker 안의 CPU 지표에는 PC의 다른 프로그램이 쓰는 CPU가 보이지 않습니다. IntelliJ를 켜 둔다면 측정 중 아무 작업도 하지 않습니다.
5. **팀 공용 Docker 인프라(개발용 Postgres·Redis·Kafka)와 쓰지 않는 모니터링(Loki·Alloy)을 멈춥니다**(`docker stop`, 측정 후 `docker start`). 측정에는 쓰지 않으면서 메모리·CPU를 씁니다.
6. 측정하는 동안 PC를 쓰지 않습니다.

**조건은 결과 파일에 자동으로 남습니다.** `measure.sh`가 k6 직전·직후에 `pc-state.sh`로 전원 모드·충전기·PC에서 열린 팀 서비스 포트 수·IntelliJ 실행 여부를 읽어 `-db.json`의 `conditions`에 적고, 히스토그램 옵션과 측정 앱에 붙은 Prometheus 여부도 함께 적습니다. 그리고 측정 중에 다음이 생기면 그 회차를 **무효**로 끝냅니다.

| 무효 조건 | 확인 방법 | 이유 |
| --- | --- | --- |
| 전원 모드나 충전기 연결이 k6 직전과 직후에 다름 | `pc-state.sh` 두 번 비교 | PC 성능이 바뀌어 처리량이 흔들림 |
| k6가 도는 동안 PC가 대기 모드(Modern Standby)에 들어감 | `standby-events.sh`: Windows 시스템 로그의 Kernel-Power 506(대기 모드 진입) 수 | CPU가 제한되다가 Docker까지 멈춤. 얕은 대기면 숫자가 나와 다른 검사를 통과할 수 있음 |

Windows가 아니면 PC 상태는 `null`로 남고 위 두 검사는 건너뜁니다. Windows인데 **확인 자체를 못 하면 측정하지 않거나 무효**로 끝냅니다: 전원 모드·충전기를 읽지 못함(`unknown`, 빈 출력), 시스템 로그를 읽지 못함(`standby-events.sh`가 `error`). 읽기 실패를 "변화 없음", "대기 모드 0번"으로 보면 검사가 조용히 사라지기 때문입니다.

실제로 겪은 일 (2026-11-10, 견적 발급): "측정 중 PC를 쓰지 않기"를 지키자 **5분 뒤 화면이 꺼지며 대기 모드**에 들어갔습니다(충전 시 화면 끄기·절전 5분). 2·3회차는 CPU가 제한된 채 숫자가 나와 검사를 통과했고, 4회차에서 Docker가 멈춰 표본이 8초만 남아 무효로 멈췄습니다. 그래서 3번 조건과 대기 모드 검사를 넣고 18회를 처음부터 다시 쟀습니다. 또 이전 측정(2026-11-09)의 전원 모드를 기록하지 않아, 다른 날 환율 결과와 비교할 때 조건을 맞출 수 없었습니다(자동 기록을 넣은 이유).

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
| `conditions` | `pc-state.sh`, `standby-events.sh`, 앱 실행 명령, Docker 네트워크 | 측정 조건: `histogram`(실제로 뜬 앱의 히스토그램 옵션), `prometheus`(측정 앱 네트워크에 Prometheus가 붙어 수집 중이면 `on`), `pcBefore`·`pcAfter`(k6 직전·직후 PC 상태), `standbyEntries`(k6 중 대기 모드 진입 수). 위 "측정 전 PC 조건" 참고 |
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
- **견적 발급 검산:** 회차마다 예열 포함 **성공 응답 수(`okAll`) ≤ 저장된 견적 수(`quoteRows.all`) ≤ 보낸 요청 수(`requestsAll`)**인지 확인하고, 아니면 멈춥니다.
  - 저장 < 성공: 성공이라고 답한 견적이 저장되지 않음(유실). 이 검산이 막으려는 것
  - 저장 > 보낸 요청: 보내지 않은 요청이 저장됨(앞 측정의 키가 남는 등)
  - 성공 < 저장: **실패로 답했는데 저장된 견적이 적어도 그 차이만큼 있다는 뜻.** 멈추지 않고 "저장 수와 성공 응답 수의 차이"를 출력합니다. 이 차이는 실패 응답 중 저장된 견적의 정확한 수가 아닙니다. 성공한 견적이 유실됐다면 실제로는 그만큼 더 많습니다(ID 대조를 하지 않아 정확한 수는 모름). Redis 견적 jar는 Redis 명령을 200ms(`spring.data.redis.timeout`)까지만 기다려 503으로 답하지만, Redis는 늦게라도 저장합니다. 2026-11-10 측정에서 앱을 막 띄운 직후 동시 300이 몰린 예열 첫 1~2초에 실패 응답이 생겼고, 저장 수가 성공 응답 수보다 38건 많았습니다. 처음에는 "성공 = 저장"을 요구해 이 경우에도 멈췄는데, 실패로 답한 요청이 저장되는 것 자체는 유실이 아니라서 위처럼 바꿨습니다. 측정 구간의 실패는 실패 요청 검사가 따로 막습니다.
  - **개수로 "유실 없음"을 증명하는 건 실패 응답이 0건일 때뿐입니다.** 요청 하나는 견적을 많아야 하나 저장하므로, 실패가 없으면 성공 = 보낸 요청이고 저장이 그 사이라면 모든 요청이 저장된 것입니다. 실패 응답이 있으면 "실패했는데 저장됨"과 "성공했는데 유실됨"이 같은 수만큼 생겨도 개수가 맞으므로, 검산은 통과시키되 "개수만으로는 증명하지 못함"을 출력합니다. 응답별 견적 ID 대조는 하지 않습니다(회차당 약 200만 건).
  - 성공 응답·보낸 요청·저장 수 중 하나라도 비었거나 숫자가 아니면 실패로 멈춥니다(비교가 오류로 거짓이 되어 그냥 통과하지 않게).
  - 측정 구간 행 수는 시각 경계가 1초 안에서 어긋나 이 검산에 쓰지 않습니다.

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
# 공식 측정은 모니터링을 멈추고 히스토그램을 끔 (아래 "모니터링 연결")
MEASURE_HISTOGRAM=false SCRIPT=quote-issue.js ./measure-compare.sh quote250 quote-db=../../../travelpay-quote-db/$J quote-redis=../../../travelpay-quote-redis/$J
```

- `measure.sh`는 견적 발급을 잴 때 측정 Redis가 1GB보다 작으면 시작하지 않습니다. 값이 다른 셸에서 이어 재면(환경 변수 없음) compose가 Redis를 기본값으로 다시 만들어 막힙니다.
- 회차마다 Redis가 키를 지웠거나(evicted) 쓰기를 거절했으면(OOM) 무효로 멈춥니다. 환율 캐시 쓰기가 거절되면 환율을 DB에서 읽게 되어 측정이 공정하지 않기 때문입니다. HTTP 오류로는 드러나지 않아 Redis 통계로 확인합니다.
- quote-redis는 환율이 캐시에 있으면(hit) 발급에 DB 연결을 쓰지 않습니다. 캐시에 없으면(miss·error) `ExchangeRateQueryService.findLatest`가 환율을 DB에서 읽어 그만큼 연결을 씁니다. 요약 표의 `캐시 hit %`가 100%인지 함께 봅니다.
- 그래서 `연결 얻은 횟수`는 0이 아니라, 캐시 miss 몫과 수집기(5초마다)·복구 스케줄러·지표 갱신(30초마다)이 쓴 만큼 남습니다(환율 조회 v1과 비슷한 수준).
- 견적 발급 요약 표에는 저장된 견적 수 / 성공 응답 수와 측정 Redis 사용 메모리(회차 중 최대)가 붙습니다.
- 환율 조회를 다시 잴 때는 `unset MEASURE_REDIS_MAXMEMORY MEASURE_REDIS_POLICY` 후 `docker compose up -d --force-recreate --wait measure-redis`로 기본값(128MB·allkeys-lru)으로 돌립니다.
- 결과(2026-11-10, #250)는 결과 파일 `results/quote250-*`와 [견적 저장소 Redis 전환 설계·측정 기록](https://claude.ai/artifact/A85umbeqG876f37rKvfidz)에 있습니다. 동시 300에서 TPS 3,051 → 14,525(4.8배), P95 226 → 33.9ms, 견적 발급의 DB 연결 사용 0. Redis 견적 jar는 순간 멈칫(200ms 넘는 Redis 명령)에 503을 내 측정 구간 실패로 무효가 되는 회차가 가끔 생깁니다. 규칙대로 그 회차만 다시 잽니다. 환경 변수는 명령마다 사라지므로 **공식 측정과 같은 값을 다시 모두 붙입니다.** 빠지면 히스토그램이 켜지거나(기본 true) 환율 조회 스크립트로 재고 견적 검산을 건너뜁니다.

```bash
export MEASURE_REDIS_MAXMEMORY=2gb MEASURE_REDIS_POLICY=noeviction   # 같은 셸에서 이어 잼
MEASURE_HISTOGRAM=false SCRIPT=quote-issue.js APP_JAR=../../../travelpay-quote-redis/$J ./measure.sh quote250-quote-redis-r5 100
SUMMARY_ONLY=1 SCRIPT=quote-issue.js ./measure-compare.sh quote250 quote-db=../../../travelpay-quote-db/$J quote-redis=../../../travelpay-quote-redis/$J
```

다시 잰 회차의 `-db.json`에서 `conditions.histogram`이 `"false"`, `conditions.prometheus`가 `"off"`인지 확인합니다.

### 원장 장애 측정 (#282 서킷 브레이커 전후)

원장이 느리거나 응답하지 않을 때 환전 서비스가 어떻게 되는지 잽니다. 원장 자리에 가짜 원장(WireMock)을 두고, 응답 지연만 바꿔 세 시나리오를 만듭니다.

| 시나리오 | 원장 지연 | 뜻 |
| --- | --- | --- |
| `normal` | 0ms | 정상 |
| `slow` | 3000ms | 느리지만 성공 (앱의 원장 응답 대기 5초 안) |
| `timeout` | 6000ms | 앱이 5초에 포기 → 결과 모름 → 주문 `PROCESSING`(202) |

두 가지를 따로 봅니다.

| 측정 | 부하 | 보는 것 |
| --- | --- | --- |
| 신청 부하 (`ledger-*`) | 환전 신청 초당 60건 + 환율 조회 초당 100건 (`exchange-order.js`) | 요청 스레드(200개)가 원장 응답을 기다리느라 바닥나는지, 원장과 상관없는 환율 조회까지 느려지는지 |
| 예약 체결 영향 (`resv-*`) | 바로 체결될 대기 예약 60건 + 견적 발급 초당 10건 (`quote-freshness.js`) | 환율 수집 스레드에서 도는 예약 체결이 원장을 기다리느라 다음 수집이 늦어져, 견적이 503이 되는지 |

**열린 모델**(constant-arrival-rate)로 잽니다. 응답과 상관없이 초당 정해진 수만큼 요청을 시작합니다. 동시 N명(닫힌 모델)으로 재면 서버가 느려질 때 가상 사용자도 같이 기다려 요청이 저절로 줄어서, 스레드가 바닥나는 상황이 가려집니다. 신청 초당 60건은 원장이 5초씩 걸릴 때 스레드 200개가 감당하는 양(200 ÷ 5 = 초당 40건)보다 많고, 원장이 정상이면 여유 있게 처리하는 양입니다.

```bash
# 1. 저장소 루트에서: 개선 전·후 커밋으로 jar 빌드 (측정 끝나도 지우지 않음)
git worktree add ../travelpay-ledger-pre282 <개선 전 커밋> && (cd ../travelpay-ledger-pre282 && ./gradlew :exchange-service:bootJar)
git worktree add ../travelpay-ledger-282 <서킷만 넣은 커밋> && (cd ../travelpay-ledger-282 && ./gradlew :exchange-service:bootJar)
git worktree add ../travelpay-ledger-282-split <예약 체결 분리 커밋> && (cd ../travelpay-ledger-282-split && ./gradlew :exchange-service:bootJar)
# #282에서 쓴 커밋: 개선 전 2afc64c, 서킷만 29991bc, 체결 분리 d701058

# 2. 측정 폴더에서: 신청은 견적(Redis 키)을 읽으므로 측정 Redis를 noeviction으로. 같은 셸에서 끝까지 잽니다
cd exchange-service/load-test
export MEASURE_REDIS_MAXMEMORY=2gb MEASURE_REDIS_POLICY=noeviction
docker compose up -d --force-recreate --wait measure-redis
J=exchange-service/build/libs/exchange-service-0.0.1-SNAPSHOT.jar

# 신청 부하: 시나리오 3개 × 3회 (회차마다 순서를 한 칸씩 돌림)
APP_JAR=../../../travelpay-ledger-pre282/$J ./measure-ledger-compare.sh ledger-pre
APP_JAR=../../../travelpay-ledger-282/$J ./measure-ledger-compare.sh ledger-post

# 예약 체결 영향: 같은 시나리오, k6 스크립트와 예약 수만 바꿈
RESERVATIONS=60 K6_SCRIPT=quote-freshness.js WARMUP_SECONDS=0 APP_JAR=../../../travelpay-ledger-pre282/$J ./measure-ledger-compare.sh resv-pre
RESERVATIONS=60 K6_SCRIPT=quote-freshness.js WARMUP_SECONDS=0 APP_JAR=../../../travelpay-ledger-282/$J ./measure-ledger-compare.sh resv-post
# 예약 체결 분리 후(서킷 + 체결 전용 스레드): 신청 경로는 바뀌지 않아 예약 영향만 다시 잼
RESERVATIONS=60 K6_SCRIPT=quote-freshness.js WARMUP_SECONDS=0 APP_JAR=../../../travelpay-ledger-282-split/$J ./measure-ledger-compare.sh resv-split

# 한 회차만: APP_JAR=... ./measure-ledger.sh <이름> <원장 지연 ms>
# 중간에 멈추면 results/<PREFIX>-progress.log의 번호부터: START_AT=5 APP_JAR=... ./measure-ledger-compare.sh ledger-post
```

- 결과는 `results/<이름>.json`(k6: 응답 시간·상태별 수·응답 못 받음)과 `results/<이름>-server.json`(요청 스레드 사용 수·커넥션 풀·CPU, 주문·예약 상태별 수, 환율 수집 횟수와 가장 긴 수집 간격, 측정 조건)입니다.
- 가짜 원장은 회차마다 새로 만들고(`--force-recreate`), 관리 API로 지연을 바꾼 뒤 다시 읽어 확인합니다. 틀린 지연으로 재면 결과가 무의미하기 때문입니다.
- 앱은 이 측정에서만 가짜 지갑·원장 주소와 요청 스레드 지표(`server.tomcat.mbeanregistry.enabled`)를 켭니다. 환경 변수가 없으면 꺼져 있어, 다른 측정 조건은 바뀌지 않습니다.
- k6 컨테이너가 가끔 1~5분 늦게 시작합니다(원인 못 찾음, 2026-11-12). 그래서 예약은 k6가 실제로 견적 요청을 보내기 시작한 뒤(Actuator prometheus에 견적 카운터가 생긴 뒤) 넣고, 서버 지표 기록은 정해진 시간이 아니라 k6가 끝났다는 표시 파일을 보고 멈춥니다. 견적 503 구간은 상대 시간이 아니라 시각(epoch)으로 남깁니다.
- 앞 측정과 같이 PC 조건(전원 모드·충전기·대기 모드)을 측정 전후에 기록하고, 맞지 않으면 그 회차는 무효입니다.
- 결과(2026-11-12, 각 시나리오 3회 중앙값, 원장 시간 초과):
  - 신청 부하: 개선 전에는 요청 스레드 200개가 120초 중 118초 동안 모두 사용 중이고, 환율 조회 p50이 0.93ms → 8.1초, 약 28%는 10초 안에 응답을 못 받았습니다. 커넥션 풀 대기는 거의 0이었습니다(원장 호출이 트랜잭션 밖이라). 서킷 후에는 스레드 평균 2.2개, 환율 조회 0.94ms, 신청은 모두 바로 202였습니다.
  - 예약 체결 영향: 개선 전에는 원장이 3초로 느리기만 해도 측정 구간에 환율 수집이 0번이 되어 견적의 약 77%가 503이었습니다. 서킷만으로는 거의 그대로였습니다(수집 1번, 503 약 72%). 체결은 한 스레드에서 하나씩 원장을 불러 10초에 2건 정도라, 서킷이 판단하는 최소 호출 수(20건)에 닿지 않아 열리지 않았습니다. 체결을 전용 스레드로 분리한 뒤에는 수집 24번(5초마다), 견적 503 0건입니다.
  - 체결 속도는 세 버전이 같습니다(원장 응답 시간에 묶임). 회차마다 예약 상태를 세는 시각이 몇 초씩 달라 체결 건수가 1~2건 다르게 보일 수 있습니다.

**Grafana 캡처**(아래 "모니터링 연결"): 공식 숫자는 모니터링을 끄고 재고, 캡처용은 `MEASURE_HISTOGRAM=true`로 1회씩 따로 잽니다. 개선 전 jar와 개선 후 jar를 1분 간격으로 연달아 재면 한 화면에 전후를 담을 수 있습니다. 캡처가 끝나면 **측정 앱을 먼저 내리고 15초 기다린 뒤** Prometheus 설정을 되돌립니다. 반대로 하면 끝 표시(staleness)가 남지 않아 마지막 값이 5분 동안 이어져 그려집니다.

| 보는 것 | Explore 쿼리 |
| --- | --- |
| 요청 스레드 | `tomcat_threads_busy_threads{name="http-nio-18084"}` (대시보드에 Tomcat 패널 없음) |
| 스케줄러 스레드(수집·복구·만료, 2개) | `executor_active_threads{name="taskScheduler"}` |
| 환율 수집 횟수(1분당) | `sum(increase(executor_completed_tasks_total{name="taskScheduler"}[1m])) - (sum(increase(tasks_scheduled_execution_seconds_count{code_function=~"recover\|expire"}[1m])) or vector(0))` (수집은 `@Scheduled`가 아니라 전용 지표가 없어, 스케줄러가 끝낸 작업에서 복구·만료를 뺌) |
| 원장 서킷 열림 | `resilience4j_circuitbreaker_state{name="exchangeLedger", state="open"}` |
| 사용자 쪽 API별 p95(k6) | `histogram_quantile(0.95, sum by (api) (rate(k6_http_req_duration_seconds[1m])))` (아래 remote write 필요) |

**사용자 쪽 응답 시간 그리기**: 서버 지표(`http_server_requests`)는 스레드가 요청을 맡은 뒤부터 재서, 요청 스레드가 꽉 차 줄 선 시간이 빠집니다. 개선 전 원장 장애에서 환율 조회 서버 p95는 5~9ms였지만 k6로는 10초였습니다. k6 결과를 Prometheus로 보내려면 Prometheus의 remote write 수신을 켜고(`compose.monitoring.k6-rw.yaml`), 측정에 `K6_PROMETHEUS_RW=1`을 붙입니다. 기본은 꺼져 있어 공식 측정 조건은 바뀌지 않습니다.

```bash
# 저장소 루트에서: 부하 시험 연결 + remote write 수신
MONITORING_LOAD_TEST_NETWORK=travelpay-load-test_default \
MONITORING_LOAD_TEST_TARGETS_FILE=./infra/monitoring/prometheus/load-test/targets.local.yml \
docker compose -f compose.monitoring.yaml -f compose.monitoring.load-test.yaml -f exchange-service/load-test/compose.monitoring.k6-rw.yaml \
  up -d --no-deps --force-recreate prometheus grafana
# 측정 폴더에서
K6_PROMETHEUS_RW=1 MEASURE_HISTOGRAM=true APP_JAR=../../../travelpay-ledger-pre282/$J ./measure-ledger.sh capture282c-pre-timeout 6000
```

**밤에 잰 회차의 시계 문제**(2026-11-13 새벽): k6가 PC 시계로 145초 만에 끝나(설정 150초) 측정 구간 끝 몇 초에 서버 표본이 없다고 무효가 난 회차가 연달아 있었습니다. 측정 뒤 Docker와 PC 시계를 비교하면 맞았고, 같은 날 낮에는 다시 나지 않아 원인은 찾지 못했습니다. 공식 측정은 모두 낮에 했고, 이 회차들은 Grafana 그림에만 썼습니다.

## 모니터링 연결 (Grafana 캡처)

공식 측정 수치는 k6와 측정 스크립트(1초 표본)로 내고, Grafana는 발표·설명용 **화면**으로만 씁니다. 모니터링을 켜면 처리량이 낮아지기 때문입니다.

| 측정 | 모니터링(Prometheus 수집) | 히스토그램 | 용도 |
| --- | --- | --- | --- |
| 공식 | 끔 | 끔 (기본, `MEASURE_HISTOGRAM` 없음 또는 `false`) | 결과 표의 수치 |
| 캡처용 | 켬 | `MEASURE_HISTOGRAM=true` | Grafana 그래프 (버전당 1회, 동시 300) |

캡처용 측정 예: `MEASURE_HISTOGRAM=true SCRIPT=quote-issue.js APP_JAR=../../../travelpay-quote-redis/$J ./measure.sh capture250-quote-redis 300`

모니터링 영향 (2026-11-10, 환율 조회 v0, 동시 300, 같은 날 같은 PC): 모니터링·히스토그램 **끔 6,073 TPS / 켬 5,809 TPS → 약 4% 낮음**. 같은 날 다른 버전도 켠 채로 11/9 결과보다 4~10% 낮았습니다(그중 약 6%는 그날 PC 상태, 끔으로 재도 11/9보다 낮음). 병목 모양(연결 대기·캐시 hit)은 켜든 끄든 같았습니다.

**연결:** 측정 환경을 띄운 뒤 저장소 루트에서. Prometheus를 측정 네트워크에도 붙여 `app:18085`를 Docker 네트워크 안에서 수집합니다(PC 포트를 지나지 않게). 자세한 설명은 [로컬 환경 설정 가이드](../../docs/guide/local-settings.md)의 "부하 시험 환경 연결"입니다.

```bash
cp infra/monitoring/prometheus/load-test/targets.example.yml infra/monitoring/prometheus/load-test/targets.local.yml   # 처음 한 번 (Git 제외 파일)
MONITORING_LOAD_TEST_NETWORK=travelpay-load-test_default \
MONITORING_LOAD_TEST_TARGETS_FILE=./infra/monitoring/prometheus/load-test/targets.local.yml \
docker compose -f compose.monitoring.yaml -f compose.monitoring.load-test.yaml up -d --no-deps prometheus grafana
```

- **Prometheus와 Grafana만 띄웁니다**(`--no-deps`). Loki·Alloy는 측정 앱 로그를 모으지 않아 측정에 쓰지 않고, 띄우면 측정 중 메모리·CPU만 씁니다(위 "측정 전 PC 조건" 5번). 이미 떠 있다면 `docker stop travelpay-monitoring-loki-1 travelpay-monitoring-alloy-1`로 멈춥니다. Grafana의 로그 패널만 비고 지표 패널은 그대로입니다.

- Grafana(`http://localhost:3000`)에서 **메트릭 환경 = `load-test`**를 고릅니다. Prometheus 대상(`localhost:9090/targets`)에서 `app:18085`가 UP인지 확인합니다.
- 공식 측정 동안은 `docker stop travelpay-monitoring-prometheus-1 travelpay-monitoring-grafana-1`로 멈추고, 캡처용 측정 전에 `docker start`로 켭니다. 공식 측정 중에는 모니터링 컨테이너가 하나도 떠 있지 않아야 합니다(`docker ps --filter name=travelpay-monitoring`이 비어 있음). Prometheus 수집 여부는 결과 파일의 `conditions.prometheus`로 확인할 수 있습니다.
- Prometheus 수집 간격은 15초라, 15초보다 짧은 측정은 그래프에 처리량이 그려지지 않습니다(합계만 남음). 캡처는 예열 30초 + 측정 2분 기본값이면 충분합니다.
- 데이터는 72시간 보관됩니다. 캡처는 그 안에 측정 시각 범위를 지정해 합니다.
- Grafana 값은 서버 안에서 잰 시간이고 1분 단위로 묶여, k6 값과 다릅니다(k6는 네트워크·앱 앞 대기를 포함). 숫자는 결과 표, 그래프는 모양만 씁니다.

**해제:** 측정 환경을 내리기 **전에** Prometheus를 기본 구성으로 되돌립니다(연결된 채로 내리면 네트워크가 사용 중이라 지워지지 않음).

```bash
docker compose -f compose.monitoring.yaml up -d --no-deps --force-recreate prometheus   # 저장 볼륨은 유지
```
