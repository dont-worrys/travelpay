# 환율 조회 부하 측정

환율 조회(`GET /api/v1/exchange-rates`)를 같은 조건으로 반복 측정하기 위한 도구입니다.
DB만 조회할 때와 Redis 캐시를 쓸 때를 같은 방법으로 재서 비교합니다(#102, Redis 캐싱 이슈).
설계와 측정 결과는 [환율 Redis 캐싱 설계·측정 기록](https://claude.ai/artifact/APBg2irhYAYYukDLjHWSA1)에 남깁니다.

## 구성

| 파일 | 하는 일 |
| --- | --- |
| `compose.yaml` | 측정 전용 Postgres(5439, pg_stat_statements 켬)와 k6(부하 생성기)를 Docker로 띄움 |
| `init/01-extensions.sql` | 측정 DB를 처음 만들 때 pg_stat_statements 확장 생성 |

필요한 것은 Docker뿐입니다. k6는 설치하지 않고 `grafana/k6` 이미지로 실행합니다.

## 측정 DB 띄우기·끄기

```bash
cd exchange-service/load-test
docker compose up -d measure-postgres   # 띄우기 (빈 DB)
docker compose down                      # 끄기 (데이터 사라짐)
```

- 공용 DB(5432)와 다른 **5439** 포트를 씁니다.
- 데이터를 남기지 않아 다시 띄우면 항상 빈 DB에서 시작합니다. 측정마다 같은 출발점에서 시작하려는 것입니다.
