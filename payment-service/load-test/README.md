# 송금 부하 측정

송금 요청(`POST /api/v1/transfers`)과 송금 조회(`GET /api/v1/transfers`, `GET /api/v1/transfers/{id}`)의 TPS·응답 시간(P95·P99)·오류율을 재고, 측정이 끝난 뒤 송금·원장·잔액이 맞는지 대사합니다.

- **Gateway 제외 측정입니다.** k6가 payment-service(8083)를 `User-Id`·`User-Role` 헤더로 직접 호출합니다. Gateway 세션 인증·CSRF는 지나지 않습니다.
- 송금 요청 한 건은 지갑 조회 2번, 회원 확인(I02) 1번, 원장 거래 등록 1번을 동기로 호출합니다. 그래서 환전 측정처럼 앱 하나만 컨테이너로 떼어 낼 수 없고, **로컬 전체 스택(PC에서 띄운 서비스)**에 k6 컨테이너만 붙여 잽니다. PC ↔ Docker 포트 전달을 지나므로 절대값보다 **같은 날 같은 조건의 비교, 정합성, 병목 위치**를 보는 데 씁니다(환전 측정 README의 "측정 환경을 Docker 안에 두는 이유" 참고).

## 구성

| 파일 | 하는 일 |
| --- | --- |
| `compose.yaml` | k6 컨테이너(`grafana/k6:1.3.0`). `host.docker.internal:8083`으로 PC의 payment-service 호출 |
| `k6-common.js` | 예열·측정을 한 시나리오로 두고 측정 구간만 집계, 결과 표·JSON (환전 측정과 같은 방식) |
| `transfer-request.js` | 송금 요청. `MODE=spread`(쌍마다 다른 송금인·수취인) / `hot`(수취인 한 명에게 집중) |
| `transfer-query.js` | 송금 조회. `QUERY=list` / `detail` / `mixed` |
| `prepare.py` | 측정용 회원·지갑을 만들고 송금인을 충전해 `data/users.json`에 남김 |
| `measure.py` | 한 단계 측정: 사전 확인 → k6 실행(서비스·DB 지표 1초 표본) → 지표 정리 → 대사 |
| `lt_common.py` | 두 Python 스크립트가 함께 쓰는 HTTP·Prometheus 도우미 (표준 라이브러리만 사용) |
| `results/`, `data/` | 결과 JSON, 준비 데이터 (커밋하지 않음) |

필요한 것: Docker, Python 3.10 이상(추가 패키지 없음), 로컬 전체 스택.

## 1. 서비스 띄우기

| 서비스 | 포트 | 비고 |
| --- | --- | --- |
| postgres, redis-session, kafka (`compose.yaml`) | 5432, 16379, … | 저장소 루트 `docker compose up -d postgres redis-session kafka` |
| eureka-server | 8761 | |
| user-service | 8081 | **측정 동안 I02 호출 제한을 올립니다** (아래) |
| wallet-service | 8082 | 충전 준비에 `MOCK_BANK_URL=http://localhost:8090` 필요 |
| payment-service | 8083 | |
| ledger-service | 8085 | |
| mock-bank | 8090 | 충전 준비에만 필요 |

**I02 호출 제한 올리기 (측정 동안만).** 송금 요청도 I02를 1번 부르므로, 기본 한도(송금인별 60초 60회)면 송금인 한 명이 초당 1건만 보낼 수 있습니다. YAML 기본값은 바꾸지 않고 실행할 때만 환경 변수로 올립니다(유저 담당 확인, 2026-11-06).

- IntelliJ Run 구성: **Program arguments**에 `--travelpay.user.recipient-lookup.limit=100000`을 넣고 Rerun. 끝나면 지우고 다시 띄웁니다.
- PowerShell 터미널: Gradle 데몬을 거치지 않게 실행해야 환경 변수가 전달됩니다(데몬을 재사용하면 터미널에서 넣은 값이 서비스에 닿지 않음, 2026-11-06 확인).

```powershell
# .env를 불러온 같은 탭에서
$env:USER_RECIPIENT_LOOKUP_LIMIT = '100000'
.\gradlew --no-daemon :user-service:bootRun
# 측정이 끝나면 지우고 다시 띄워 기본값(60)으로 되돌립니다
Remove-Item Env:USER_RECIPIENT_LOOKUP_LIMIT
```

적용 확인: 새 요청자 ID로 수취인 조회를 61번 넘게 보내 429가 나오지 않으면 됩니다. 한도에 걸리면 결과에 429가 남고 측정은 무효입니다(송금인마다 60건만 성공).

### 측정 전 PC 조건 (같은 날 비교할 측정끼리 같게)

충전기 연결, Windows 전원 모드 "최고 성능", 브라우저·빌드 등 무거운 작업 끄기, 측정 중 PC 사용하지 않기. 서비스를 PC에서 띄우므로 IntelliJ는 켜 둘 수밖에 없지만, 측정마다 같은 서비스 구성을 유지합니다.

## 2. 데이터 준비 (`prepare.py`)

```bash
cd payment-service/load-test
python prepare.py                       # 송금인 20명 × 20,000원, 수취인 20명 + 집중 수취인 1명
python prepare.py --pairs 40 --charge 10000
```

- 송금인 실명은 mock-bank 시드 계좌 예금주와 같아야 합니다(기본 `유재석`, 계좌 `004`/`12340101234567`). 다르면 충전이 `BANK_HOLDER_MISMATCH`로 실패합니다.
- 충전은 모두 접수한 뒤 상태를 5초마다 확인해 **전부 `COMPLETED`일 때만** 끝납니다. `FAILED`·`MANUAL_REVIEW`가 나오면 멈춥니다(대개 `MOCK_BANK_URL` 누락).
- mock-bank 계좌 잔액은 DB에 남아 반복하면 줄어듭니다. 20명 × 20,000원 = 400,000원.
- 송금 금액 기본값이 1원이라 송금인 한 명이 20,000건까지 보낼 수 있습니다. 측정 중 잔액 부족(422)이 나오면 충전액을 늘립니다.
- 다시 실행하면 새 회원으로 `data/users.json`을 덮어씁니다.

## 3. 측정 (`measure.py`)

```bash
python measure.py spread 20                    # 송금 요청, 쌍마다 다른 송금인·수취인, 동시 요청 20
python measure.py hot 20 --mode hot            # 송금 요청, 모두 집중 수취인에게
python measure.py query 50 --script transfer-query.js --query mixed   # 송금 조회
```

| 옵션 | 기본 | 뜻 |
| --- | --- | --- |
| `<LABEL> <VUS>` | | 결과 파일 이름, 동시 요청 수(쉬는 시간 없는 가상 사용자 수) |
| `--mode` | spread | `spread`는 VUS ≤ 준비한 쌍 수여야 함(송금인 잠금이 겹치지 않게) |
| `--amount` | 1 | 송금 금액(원) |
| `--warmup` / `--duration` | 15 / 60 | 예열·측정 초. 비교할 측정끼리 같게 |
| `--recovery-timeout` | 600 | 측정 후 PROCESSING 복구를 기다릴 최대 초 |

순서:

1. **사전 확인**: 네 서비스 health, 남은 `PROCESSING` 송금이 0건인지(복구 재전송이 섞이지 않게).
2. **k6 실행**: 그동안 user·wallet·payment·ledger의 `/actuator/prometheus`와 Postgres(`pg_stat_activity`, `pg_stat_database`)를 1초마다 읽습니다.
3. **지표 정리**: 측정 구간(예열 제외 1분) 양끝 표본의 차이로 계산합니다.
4. **대사(송금 요청만)**: `PROCESSING`이 없어질 때까지 기다린 뒤(복구 스케줄러), 관리자 송금 목록과 원장 기간 목록(`/internal/v1/ledger/transactions/period`)을 맞춰 봅니다.

### 결과

`results/<LABEL>-vus<VUS>.json` (k6, 클라이언트 쪽)

| 값 | 뜻 |
| --- | --- |
| `successTps`, `totalTps` | 성공(송금 201 `COMPLETED` / 조회 200) TPS, 전체 TPS |
| `latencyMs` | 평균·P50·P95·P99·최대 |
| `statusCounts` | 성공, 202(원장 결과 모름 → PROCESSING), 422(잔액 부족 등), 429, 기타 4xx, 5xx, 응답 없음 |
| `errorRate` | 429·기타 4xx·5xx·응답 없음의 비율(%). 202·422는 서버가 정상 처리한 응답이라 따로 셈 |

`results/<LABEL>-vus<VUS>-metrics.json` (서버·DB 쪽, 대사)

| 값 | 출처 | 뜻 |
| --- | --- | --- |
| `services.<서비스>.<경로>.avgMs` | `http_server_requests_seconds` 합계·횟수 차이 | 서비스가 그 요청을 처리한 평균 시간. payment 송금 요청 시간에서 wallet·user·ledger 시간을 빼면 payment 자체 + 네트워크 시간 |
| `services.<서비스>.hikariPending` / `hikariActive` | `hikaricp_connections_*` | 커넥션 대기 수·사용 중 수 평균·최대 (모든 서비스 기본 최대 10) |
| `services.<서비스>.processCpuPercentAvg`, `systemCpuPercent` | `process_cpu_usage`, `system_cpu_usage` | 서비스 JVM CPU, PC 전체 CPU |
| `db.ledgerLockWaits` | `pg_stat_activity` (`ledger_app`, `wait_event_type='Lock'`) | 원장 계정 행 잠금(`FOR UPDATE`)을 기다리는 세션 수 평균·최대 |
| `db.ledgerActiveSessions`, `paymentActiveSessions` | `pg_stat_activity` (`state='active'`) | 실행 중 쿼리 수 |
| `db.deadlocksDuringMeasure` | `pg_stat_database.deadlocks` 차이 | 측정 중 교착 수 (DB 전체) |
| `reconciliation` | 관리자 송금 목록, 원장 기간 목록, 지갑 잔액 | 아래 불변식과 위반 목록, 상태별 건수, PROCESSING 복구에 걸린 초 |

대사 불변식 (정합성 통합 테스트와 같은 기준):

1. `COMPLETED` 송금마다 원장 `POSTED` 1건, 항목이 송금인 지갑 DEBIT `TRANSFER_OUT` · 수취인 지갑 CREDIT `TRANSFER_IN` · 같은 통화·금액
2. `FAILED` 송금은 원장 `POSTED` 없음
3. 송금 없이 원장에만 `POSTED`인 거래 없음, reference당 원장 거래 1건
4. 측정 참여자 잔액 = 측정 전 잔액 ± 완료 송금, 음수 없음, 합계 보존
5. 복구 후 `PROCESSING` 0건

오류 응답이나 위반이 하나라도 있으면 `measure.py`가 종료 코드 1과 함께 알리고, 그 측정은 원인을 확인하기 전까지 기준값으로 쓰지 않습니다.

## 주의

- 원장 기간 목록은 `createdAt`이 커밋 전에 정해지므로, 대사는 측정이 끝나고 복구까지 기다린 뒤 닫힌 구간으로 읽습니다(원장 가이드 「커밋 지연 한계와 대사」).
- 측정 구간에는 다른 송금 트래픽이 없어야 합니다(관리자 목록을 기간으로 읽음).
- 조회 측정(`transfer-query.js`)은 송금 요청 측정을 먼저 돌려 송금 내역이 쌓인 뒤에 합니다.
- Postgres 컨테이너 이름·관리자 계정이 다르면 `PG_CONTAINER`, `POSTGRES_USER`, `POSTGRES_DB` 환경 변수로 바꿉니다. 읽지 못하면 DB 지표만 빠지고(`db.error`) 측정은 계속됩니다.

## 측정 결과 (2026-11-06)

### 조건

- 코드: dev `9145ba9`. 로컬 전체 스택(eureka·user·wallet·payment·ledger, Postgres·Redis 컨테이너)을 노트북 한 대에서 실행, k6만 컨테이너
- Gateway 제외(payment 직접 호출), I02 한도는 측정 동안만 100,000으로 올림
- 동시 요청 20, 예열 15초 + 측정 60초, **각 1회** (중앙값 아님), 송금 1원, PC 조건(충전기·최고 성능·무거운 작업 끔) 맞춤
- `spread`: 송금인 20명이 각자 다른 수취인에게 / `hot`: 송금인 20명이 수취인 한 명에게

### 결과

| 항목 | spread 20 | hot 20 |
| --- | --- | --- |
| 성공 TPS | 161.3 | 93.5 (−42%) |
| 응답 P50 / P95 / P99 (ms) | 118.7 / 163.8 / 210.0 | 145.1 / 631.4 / 837.0 |
| 최대 / 1초 초과 | 319ms / 0건 | 2,271ms / 33건 |
| 오류 (202·422·429·기타 4xx·5xx·응답 없음) | 0 | 0 |
| 서버 평균 (ms): payment 송금 / 원장 거래 등록 / 지갑 조회 / I02 | 119.3 / 41.5 / 10.5 / 12.0 | 211.7 / 166.2 / 5.2 / 6.4 |
| 원장 커넥션 풀: 사용 중 평균·최대 / 대기 평균·최대 | 5.8·10 / 0.07·2 | 9.9·10 / 5.5·6 |
| payment 커넥션 풀: 사용 중 평균·최대 / 대기 최대 | 5.2·10 / 2 | 1.1·3 / 0 |
| 원장 잠금 대기 세션 평균·최대 | 0 / 0 | 8.3 / 9 |
| 교착 | 0 | 0 |
| PC CPU 평균 / 최대 | 97.5% / 100% | 78.0% / 93.3% |
| 대사 (측정 데이터 누적 송금 / 위반) | 12,128건 / 0 | 7,005건 / 0 |

대사는 두 측정 모두 통과했습니다. `COMPLETED` 송금마다 원장 `POSTED` 1건(항목 일치), 원장에만 있는 거래 없음, `PROCESSING` 0건, 참여자 잔액 합계 400,000원 보존.

### 분석

**송금 요청 한 건의 시간 (spread 20, 서버 평균 119.3ms)**: 원장 41.5(35%) + 지갑 조회 2번 21.0(18%) + I02 12.0(10%) + payment 자체 약 45(38%, 송금 저장·결과 반영 커밋 2번, Feign, CPU 경합). 외부 호출이 약 62%입니다.

**병목 1, 수취인 집중 시 원장 계정 잠금 (주요)**
- 원장은 거래마다 송금인·수취인 계정 행을 계정 ID 순서로 `FOR UPDATE` 잠급니다. 수취인이 한 명이면 모든 송금이 그 행을 차례로 기다립니다.
- hot 20에서 원장 커넥션 10개 중 평균 8.3개가 잠금 대기였고 커넥션 풀이 고갈됐습니다(대기 평균 5.5). 원장 처리 41 → 166ms, TPS −42%, P95 3.9배.
- CPU 여유(78%)가 있었으므로 자원 부족이 아니라 잠금 직렬화입니다. 원장 커넥션 풀을 늘려도 기다리는 연결만 늘어 효과가 없습니다.
- 한계선은 원장 설정입니다: 커넥션 대기 2초(`connection-timeout`), 거래 트랜잭션 3초. hot 최대 2.27초로 가깝습니다. 넘으면 원장이 오류로 응답하고, 송금은 결과 모름(202 `PROCESSING`)으로 두었다가 복구 스케줄러가 확정합니다. 정합성은 유지되지만 응답이 "처리 중"으로 바뀝니다.

**병목 2, 측정 환경**: spread 20에서 PC CPU가 97.5%였습니다. 161 TPS는 앱이 아니라 노트북 한 대(k6·서비스 5개·DB)의 한계라, 이 환경으로는 앱 처리량 상한을 판단할 수 없습니다. 비교(spread vs hot)와 정합성 확인에만 씁니다.

**병목 3, payment 커넥션 풀 (경미)**: spread 20에서 사용 중이 순간 10, 대기가 최대 2였습니다. 원장 호출 동안은 커넥션을 잡지 않도록 되어 있어(저장·결과 반영만 짧은 트랜잭션) 지금은 문제가 아닙니다.

### 개선 후보 (구현하지 않음)

| 후보 | 담당 | 판단 |
| --- | --- | --- |
| 집중 계정 대응(잠금 범위 축소, 수취 계정 분할, 입금 지연 반영 등) | 원장 | 원장 설계 변경. 측정 근거와 함께 제안 |
| 잠금·커넥션 대기 한도 초과 시 원장 응답 코드 확인 | 원장 | 송금 어댑터가 결과 모름으로 받는지 확인용 |
| 지갑 조회 2번·I02 1번 병렬 호출 | 송금 | 최대 약 20ms 절감 추정. 오류 우선순위가 바뀌고 스레드 사용이 늘어 보류 |
| 한 수취인에게 송금이 몰리는 시나리오가 실제로 있는지 | 팀 | 송금은 개인 간이라 영향이 작을 수 있음. 결제(가맹점 수취)도 같은 원장 구조 |
| 앱 처리량 상한 재측정 | 측정 | 서비스를 다른 머신에 두거나 CPU가 포화되지 않는 동시 요청 수로 |
