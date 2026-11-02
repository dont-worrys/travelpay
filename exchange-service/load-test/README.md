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
