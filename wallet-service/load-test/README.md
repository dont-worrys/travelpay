# 충전 부하 측정

충전 접수(`POST /api/v1/charges`)부터 SAGA 종결(은행 출금 → 원장 적립)까지를 같은 조건에서 재고, 끝난 뒤 은행 출금 정합성과 운영 대사 API 결과를 확인합니다.

- 충전은 접수 후 202로 응답하고 Outbox(1초·50건) → Kafka(`wallet.charge.requested`, 파티션 3) → SAGA로 처리됩니다. 그래서 **접수 응답 시간**과 **종결까지의 완료 처리량·지연**을 따로 봅니다.
- 목적은 **병목 위치와 정합성 확인**입니다. 로컬 전체 스택(PC)에 k6 컨테이너만 붙이므로 절대 처리량이 아니라 **같은 날 같은 조건의 비교**(예: SAGA 컨슈머 동시성 1 vs 3)와 대사 결과를 봅니다.
- **Gateway 제외 측정입니다.** k6가 wallet-service(8082)를 `User-Id`·`User-Role` 헤더로 직접 호출합니다.
- 부하는 VU 닫힌 루프가 아니라 **constant-arrival-rate**(초당 `--rate`건, 총 `--count`건)입니다. 서버가 느려져도 요청 시작 간격이 유지되고, VU가 모자라 시작하지 못한 건은 `dropped_iterations`로 남습니다. 요청마다 새 `Idempotency-Key`(UUID), 측정 회원을 돌아가며 씁니다.

## 구성

| 파일 | 하는 일 |
| --- | --- |
| `compose.yaml` | k6 컨테이너(`grafana/k6:1.3.0`). `host.docker.internal:8082`로 PC의 wallet-service 호출 |
| `charge-request.js` | 충전 접수 부하(constant-arrival-rate), 접수 P50·P95·상태별 건수·`dropped_iterations` |
| `prepare.py` | 측정 회원·지갑을 만들고 회원마다 mock-bank 계좌를 DB에 INSERT, `data/users.json`에 남김 |
| `measure.py` | 한 번 측정: 사전 확인 → k6(서비스·DB 지표 1초 표본) → 종결 대기 → 지표 → 출금 정합성 → 대사 |
| `lt_common.py` | HTTP·Prometheus·psql 도우미 (표준 라이브러리만 사용, 송금 측정과 같은 방식) |
| `results/`, `data/` | 결과 JSON, 준비 데이터 (커밋하지 않음) |

필요한 것: Docker, Python 3.10 이상(추가 패키지 없음), 로컬 전체 스택.

## 1. 서비스 띄우기

| 서비스 | 포트 | 비고 |
| --- | --- | --- |
| postgres, redis-session, kafka (저장소 루트 `compose.yaml`) | 5432, 16379, 9092 | `docker compose up -d postgres redis-session kafka` |
| eureka-server | 8761 | |
| user-service | 8081 | 충전 접수마다 I03(`funding-identity`) 1번. 호출 한도 없음 |
| wallet-service | 8082 | 아래 실행 조건 |
| ledger-service | 8085 | |
| mock-bank | 8090 | |

### 지갑 실행 조건

환경 변수: `MOCK_BANK_URL=http://localhost:8090`, `LEDGER_SERVICE_URL=http://localhost:8085`(지갑 개설에도 필요), `USER_SERVICE_URL=http://localhost:8081`.
**`BANK_FAULT_INJECTION_MODE`는 비워 둡니다**(값이 있으면 출금 첫 시도마다 장애를 주입해 측정이 무효).

실행 인자(IntelliJ Run 구성의 Program arguments 등):

```
--travelpay.wallet.charge.reconciliation.enabled=false
--travelpay.wallet.charge.reconciliation.grace=10s
--management.metrics.distribution.percentiles-histogram.http.server.requests=true
--travelpay.wallet.charge.saga.consumer.concurrency=<측정값>
```

- `reconciliation.enabled=false`: 10분 주기 대사 스케줄러가 측정 중에 돌지 않게 합니다(수동 실행은 그대로 됨).
- `reconciliation.grace=10s`: `measure.py`가 기간 끝을 `지금 − 10초`로 수동 대사합니다. 기본값(5m)이면 400입니다.
- 동시성은 앱 재기동으로 바꿉니다. `measure.py --concurrency`는 결과에 남기는 값일 뿐 실제 설정을 바꾸지 않으므로 두 값을 맞춥니다.

### 동시성을 3보다 크게 잴 때

토픽 파티션(코드 선언 3)보다 컨슈머가 많으면 남는 컨슈머는 놉니다. 측정 전에 브로커 토픽 파티션을 먼저 늘립니다.

Git Bash에서는 `/opt/...` 경로가 Windows 경로(`C:/Program Files/Git/opt/...`)로 바뀌어 실패하므로 `MSYS_NO_PATHCONV=1`을 붙입니다(PowerShell·macOS·Linux에서는 없어도 됨).

```bash
MSYS_NO_PATHCONV=1 docker exec travelpay-local-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --alter --topic wallet.charge.requested --partitions <N>
MSYS_NO_PATHCONV=1 docker exec travelpay-local-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --topic wallet.charge.requested     # PartitionCount 확인
```

이때 지갑 기동 로그의 `충전 SAGA 컨슈머 동시성이 토픽 파티션 수보다 큼 … partitions=3` WARN은 **코드 선언값(3)과만 비교**한 것이라(브로커 조회 안 함) 무시해도 됩니다.

**되돌리기.** 파티션은 줄일 수 없으므로 토픽을 지우고 다시 만듭니다. **지갑을 끄고** 처리 중 충전이 없는 상태에서 토픽을 지운 뒤 지갑을 다시 띄우면, 코드에 선언한 파티션 수(3)로 토픽이 다시 만들어집니다. 아직 Kafka로 넘어가지 않은 건은 Outbox에 남아 있다가 새 토픽으로 발행됩니다. Kafka 볼륨을 지우는 방법은 다른 토픽까지 모두 사라지므로 쓰지 않습니다.

```bash
MSYS_NO_PATHCONV=1 docker exec travelpay-local-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --delete --topic wallet.charge.requested
```

### 측정 전 PC 조건 (같은 날 비교할 측정끼리 같게)

충전기 연결, Windows 전원 모드 "최고 성능", 브라우저·빌드 등 무거운 작업 끄기, 측정 중 PC 사용하지 않기.

## 2. 데이터 준비 (`prepare.py`)

```bash
cd wallet-service/load-test
python prepare.py                 # 회원 50명(실명 유재석) + 지갑 + 회원마다 mock-bank 계좌
python prepare.py --users 100 --bank-balance 1000000000000
```

- 계좌는 `mock_bank.bank_accounts`에 직접 INSERT합니다: 예금주 `유재석`, 은행 `004`, 계좌번호 `77` + 실행 시각(UTC `yyMMddHHmmss`) + 순번 4자리(실행끼리 겹치지 않음), 잔액 기본 1조 원. Flyway 시드 계좌는 건드리지 않습니다.
- 충전은 하지 않습니다. 회원 수(`--users`)와 충전 건수(`measure.py --count`)는 별개이며, 충전은 회원을 돌아가며 씁니다.
- 다시 실행하면 새 회원·계좌로 `data/users.json`을 덮어씁니다(이전 계좌는 DB에 남음).

## 3. 측정 (`measure.py`)

```bash
python measure.py c1 --count 1000 --rate 50 --concurrency 1
python measure.py c3 --count 1000 --rate 50 --concurrency 3
```

| 옵션 | 기본 | 뜻 |
| --- | --- | --- |
| `<LABEL>` | | 결과 파일 이름 |
| `--count` / `--rate` | (필수) | 총 시작 건수 / 초당 시작 건수. 실행 시간 = ⌈count ÷ rate⌉초 |
| `--concurrency` | (필수) | 지갑 SAGA 컨슈머 동시성(메타데이터) |
| `--amount` | 10000 | 충전 금액(원, KRW). 최대값 검증 없음 |
| `--pre-vus` / `--max-vus` | max(20, rate) / max(100, rate×4) | `dropped_iterations`가 나오면 `--max-vus`를 올립니다 |
| `--drain-timeout` | 600 (`--fault-rate` > 0이면 900) | k6 종료 후 모든 충전 종결을 기다릴 최대 초. 지정하면 그 값을 씀. 장애 측정은 900 이상 권장 |
| `--fault-rate` | 0.0 | Mock 은행 출금 응답 유실 비율(메타데이터, 0.0~1.0). 실제 비율은 Mock 은행 실행 인자로 넣음 |

### 측정 절차 (비교할 조건마다)

1. 지갑 실행 인자의 동시성을 바꾸고 **재기동**합니다(3 초과면 위의 파티션 먼저).
2. **예열 1회**: `python measure.py warmup --count 100 --rate <측정과 같은 값> --concurrency <값>` — JIT·커넥션 풀·Kafka 할당을 데우는 용도이며 **결과는 버립니다**.
3. **같은 조건으로 측정 3회** (`c1-1`, `c1-2`, `c1-3`처럼 라벨만 바꿈). 매번 종료 코드 0인지 확인합니다.
4. 3회의 **중앙값**을 기록합니다(완료 처리량, 완료 지연 P50·P95, 접수 P50·P95, 서비스별 avgMs).

### 장애 측정 (출금 응답 유실)

Mock 은행이 출금 2xx 응답을 비율만큼 처리·커밋한 뒤 500으로 바꿉니다(같은 requestId 재요청에도 적용). 지갑은 결과 모름으로 두고, 복구 스케줄러가 같은 requestId로 다시 보냅니다(10초 주기, 30초부터 두 배 백오프, 5회 후 `MANUAL_REVIEW`).

1. Mock 은행을 실행 인자 `--mock-bank.fault.withdrawal-lost-rate=<비율>`(예: `0.1`)로 **재기동**합니다(`prod` 프로필이 아니어야 함). 지갑은 측정할 조건의 동시성과 Outbox batch-size로 띄웁니다(예: `--travelpay.wallet.charge.saga.consumer.concurrency=3`, `--travelpay.wallet.outbox.publisher.batch-size=200`).
2. **비율이 적용됐는지 직접 확인합니다.** 출금을 몇 번 보내 500이 섞여 나오면 적용된 것입니다. Git Bash에서는 한글 본문이 깨질 수 있어 UTF-8 파일로 보냅니다(시드 계좌에서 1원씩 출금되며 측정 계좌와는 무관).

   ```bash
   for i in $(seq 1 10); do
     id=$(python -c "import uuid; print(uuid.uuid4())" | tr -d '\r')
     printf '{"requestId":"%s","bankCode":"004","accountNumber":"12340101234567","holderName":"유재석","amount":1}' "$id" > body.json
     curl -s -o /dev/null -w "%{http_code} " -X POST localhost:8090/internal/v1/bank/withdrawals \
       -H 'Content-Type: application/json; charset=UTF-8' --data-binary @body.json
   done; echo; rm body.json
   ```

3. `python measure.py f10 --count 1000 --rate 50 --concurrency 3 --fault-rate 0.1 --drain-timeout 900`
   - `--fault-rate`는 Mock 은행 실행 인자와 같은 값으로 넣습니다(결과에 남기는 값).
   - `--drain-timeout`은 900 이상을 권장합니다. 응답이 계속 유실되면 30·60·120·240초 백오프 뒤 `MANUAL_REVIEW`가 되므로 종결까지 약 7~8분 걸릴 수 있습니다.
   - **처리 한도보다 느린 속도로 넣습니다.** 큐에서 30초 넘게 기다린 충전(`REQUESTED`)도 복구 스케줄러가 처리해 `recoveredCharges`에 섞이기 때문입니다. 예: 동시성 3, Outbox batch-size 200이면 `--rate 50`.
4. 장애 측정 뒤에는 Mock 은행의 유실 비율을 **0으로 되돌리고 재기동**합니다. 남아 있으면 다음 기준선 측정이 종료 코드 1이 됩니다(아래 실패 조건).

장애 측정에서 `charges.completionTps`는 복구 대기 시간이 구간에 포함되어 처리량으로 해석하지 않습니다. 볼 값은 `fault` 블록과 출금 정합성입니다. 예: 출금 호출 26번, 은행 출금 성공 20건이면 재시도 6번이 중복 출금으로 이어지지 않은 것입니다.

### measure.py가 하는 일

1. **사전 확인**: user·wallet·ledger·mock-bank `/actuator/health`, 처리 중(종료 상태가 아닌) 충전 0건(전체), 측정 계좌 수 = 회원 수. 측정 계좌 잔액 합계를 기록합니다.
2. **k6 실행**. 그동안 1초마다 네 서비스 `/actuator/prometheus`와 Postgres(`pg_stat_activity`, `pg_stat_database`, `outbox_events`)를 읽습니다. 종결 대기가 끝날 때까지 계속 읽습니다.
3. **종결 대기**: 이번 측정 충전(측정 회원 + `created_at ≥ k6 시작 − 5초`)이 모두 `COMPLETED`·`FAILED`·`REFUNDED`·`MANUAL_REVIEW`가 될 때까지 5초 간격.
4. **지표** (아래 표).
5. **출금 정합성** (mock-bank DB, 측정 계좌·같은 기간의 `SUCCEEDED` 거래):
   - DEBIT 건수·합계 = `COMPLETED`+`REFUNDED` 충전 건수·합계
   - REFUND 건수·합계 = `REFUNDED` 충전 건수·합계
   - 측정 계좌 잔액 감소 = DEBIT 합계 − REFUND 합계
6. **대사**: 마지막 종결 후 10초가 넘게 지난 뒤 `POST /api/v1/admin/charges/reconciliations?from=<첫 created_at>&to=<지금 − 10초>`(`User-Role: MASTER`, 동기). 요약·소요 시간을 남기고, 어긋남·판정 보류가 있으면 결과 목록(최대 100건)을 붙입니다.

### 결과

`results/<LABEL>-c<CONCURRENCY>.json` (k6, 클라이언트 쪽)

| 값 | 뜻 |
| --- | --- |
| `requests`, `droppedIterations` | 보낸 요청 수, VU가 모자라 시작하지 못한 건수 |
| `latencyMs` | 접수 응답 평균·P50·P95·P99·최대 |
| `statusCounts`, `errorResponses` | 202·200·4xx·5xx·응답 없음, 202 외 합계 |

`results/<LABEL>-c<CONCURRENCY>-metrics.json` (서버·DB, 정합성, 대사)

| 값 | 출처 | 뜻 |
| --- | --- | --- |
| `charges.accepted`, `statuses` | `charge_requests` | 실제 접수 건수(DB), 상태별 건수·금액 |
| `charges.completionTps` | `charge_requests.created_at`, `charge_status_history` | 접수 건수 ÷ (첫 `created_at` ~ 마지막 종결 시각) |
| `charges.completionLatencyMs` | `charge_status_history` | `COMPLETED` 기록 시각 − `created_at`의 P50·P95 |
| `services.<서비스>.<경로>.avgMs` | `http_server_requests_seconds` 합계·횟수 차이 | wallet 충전 접수, user I03, ledger 거래 등록, mock-bank 출금(·환불) 평균 처리 시간 |
| `services.<서비스>.hikariPending` / `hikariActive` | `hikaricp_connections_*` | 커넥션 대기·사용 평균·최대 (wallet·ledger를 봄) |
| `services.systemCpuPercent` | wallet `system_cpu_usage` | PC 전체 CPU |
| `db.ledgerLockWaits` | `pg_stat_activity` (`ledger_app`, `wait_event_type='Lock'`) | 원장 잠금 대기 세션 수 |
| `db.deadlocksDuringMeasure` | `pg_stat_database.deadlocks` 차이 | 측정 중 교착 수(DB 전체) |
| `db.outboxPending` | `outbox_events.status='PENDING'` | Outbox 미발행 건수 평균·최대 |
| `bankConsistency` | `mock_bank.bank_transactions`, `bank_accounts` | 위 세 등식과 위반 목록 |
| `reconciliation` | 대사 API | `summary`(status, targetCount, matchedCount, mismatchCount, unverifiableCount …), `elapsedSeconds`, 어긋남 목록 |
| `fault` | 아래 표 | 장애(응답 유실) 지표. 기존 지표·실패 조건과 별개로 추가 |
| `failures` | | 종료 코드 1의 이유 |

서비스 지표 구간은 k6 시작 ~ 마지막 종결 시각입니다(접수 뒤에도 SAGA가 은행·원장을 부르므로).

`fault` 블록:

| 값 | 출처 | 뜻 |
| --- | --- | --- |
| `faultRate` | `--fault-rate` | 측정에 넣은 유실 비율(메타데이터) |
| `lostResponses` | mock-bank `mock_bank_fault_withdrawal_lost_rate_total` | k6 시작 전에 읽은 시작값 ~ 구간 끝(마지막 종결) 표본의 증가분. **응답 수**라, 같은 충전의 재시도 응답도 유실될 수 있어 유실된 충전 수보다 클 수 있음. 시작값이나 끝 표본에 카운터가 없으면 `null` |
| `bankWithdrawalCalls` | `services.mock-bank."POST /internal/v1/bank/withdrawals".calls` | 측정 구간 은행 출금 호출 수(재시도 포함) |
| `acceptedCharges` | `charges.accepted` | 측정 접수 건수. `bankWithdrawalCalls`와의 차이는 재시도분이며 기록만 하고 실패로 보지 않음 |
| `recoveredCharges` | `charge_status_history.trace_id` | 측정 충전 중 상태 이력에 `trace_id`가 `recovery-`로 시작하는 행이 하나 이상 있는 충전 수 = **복구 스케줄러가 한 번 이상 처리한 충전**. 복구 성공 여부는 기존 실패 조건(`MANUAL_REVIEW` 등)이 판정 |
| `recoveredLatencyMs` | `charge_status_history` | `recoveredCharges` 중 `COMPLETED` 이력이 있는 충전의 `COMPLETED` 기록 시각 − `created_at` P50·P95·건수 (`charges.completionLatencyMs`와 같은 계산, 완료 이력의 `trace_id`는 보지 않음) |
| `definition` | | `recoveredCharges` 정의 문구 |

`recoveredCharges`를 읽을 때:

- 복구 스케줄러는 상태가 `REQUESTED`·`BANK_PROCESSING`·`CREDIT_PROCESSING`·`REFUND_PROCESSING`이고 `updated_at`이 30초(× 두 배 백오프) 넘게 지난 충전을 집어 갑니다. 응답 유실이 없어도 큐에서 30초 넘게 기다린 충전이 섞일 수 있으므로, `--fault-rate 0` 측정에서 0이 아니면 큐 적체로 봅니다.
- 상태 이력은 상태가 바뀔 때만 남습니다. 복구 재전송이 다시 500을 받으면(결과 모름, `attempt_count`만 +1) 이력이 없고, 이후 성공 전이나 `MANUAL_REVIEW` 전이 때 `recovery-` 행이 남습니다.

**종료 코드 1** (그 측정은 원인을 확인하기 전까지 기준값으로 쓰지 않습니다):

- 접수: 202 외 응답, `dropped_iterations` > 0, k6 응답 집계(202·200·4xx·5xx·무응답 합) ≠ `--count`, DB 접수 건수(`charges.accepted`) ≠ k6 202 응답 건수
- 충전 결과: `MANUAL_REVIEW` 1건 이상, `FAILED`·`REFUNDED` 1건 이상, 종결 대기 시간 초과, 접수 0건, 종결 상태 이력 없음, 완료 지연 집계 건수 ≠ `COMPLETED` 건수
- 서비스 지표: 구간 시작·끝 경계 표본이 없는 서비스(user·wallet·ledger·mock-bank 중), 서비스 표본 스레드 중단
- DB 표본: 수집 실패(`docker exec` 0이 아닌 종료 코드 포함, `db.error`), 수집이 끝나지 않음, 측정 구간 표본 0개
- 출금 정합성 위반
- 대사: 실행 실패(HTTP 200 아님), 응답 필드 누락, 대상 건수 < 측정 접수 건수, status ≠ `COMPLETED` 또는 어긋남·판정 보류 1건 이상
- 장애 설정: 유실 카운터가 수집되지 않음(`lostResponses` = `null`, `--fault-rate`와 관계없음), `--fault-rate` > 0인데 `lostResponses` = 0(Mock 은행 실행 인자 확인), `--fault-rate` = 0인데 `lostResponses` > 0(Mock 은행 유실 비율이 남아 있음)

k6 결과 파일이 없거나 JSON이 아니거나 필수 필드가 빠지면 지표를 만들지 않고 바로 멈춥니다(종료 코드 1). psql 조회 실패도 같습니다.

## 주의

- 측정 중에는 다른 충전 트래픽이 없어야 합니다. 대사 기간에는 그 시간대의 측정 외 충전도 포함됩니다.
- 처리 중 충전이 남아 있으면 사전 확인에서 멈춥니다(복구 스케줄러 재실행이 섞임). 남은 건이 종결되거나 정리된 뒤 측정합니다.
- 측정 계좌 잔액은 반복할수록 줄어듭니다(기본 1조 원이라 사실상 바닥나지 않음).
- Postgres 컨테이너 이름·관리자 계정이 다르면 `PG_CONTAINER`(기본 `travelpay-local-postgres-1`), `POSTGRES_USER`(기본 `travelpay_admin`), `POSTGRES_DB`(기본 `travelpay`) 환경 변수로 바꿉니다. 사전 확인·지표·정합성은 psql이 필요하고, 1초 DB 표본만 실패하면 `db.error`로 남기고 계속합니다.
- 서비스 주소가 다르면 `USER_URL`, `WALLET_URL`, `LEDGER_URL`, `MOCK_BANK_URL`(k6는 `BASE_URL`)로 바꿉니다.

## 측정 결과 (2026-11-08)

### 조건

- 코드: dev `c316068`. 로컬 스택(eureka·user·ledger·mock-bank·wallet, Postgres·Redis·Kafka 컨테이너)을 노트북 한 대에서 실행, k6만 컨테이너
- Gateway 제외(wallet 직접 호출), 충전 1건 10,000원, 측정 회원 50명(회원마다 다른 mock-bank 계좌)
- 부하는 constant-arrival-rate로 고정 건수를 일정 속도로 넣음. 접수 구간은 모든 조건에서 20초
- 지갑 재기동(설정 변경) 후 예열 1회(결과 제외) → 같은 조건 3회 → **중앙값**
- 변수는 한 번에 하나씩 바꿈: 컨슈머 동시성 → 넣는 속도 → Outbox batch-size → 동시성
- 동시성 10은 브로커 토픽 파티션을 10으로 늘려 측정하고, 끝난 뒤 토픽을 지워 3으로 되돌림
- PC 조건(충전기, 최고 성능, 무거운 작업 끔) 맞춤. c3 초당 50건 측정 중 브라우저 창 1개가 열려 있었으나 PC CPU 차이 1%p로 영향 없음으로 판단

### 결과 (중앙값)

| 조건 | 동시성 | 넣은 속도 (건/초) | batch-size | 완료 처리량 (건/초) | 완료 지연 P50 / P95 (ms) | 접수 P95 (ms) |
| --- | --- | --- | --- | --- | --- | --- |
| 기준선 | 1 | 50 | 50 | **21.0** | 14,234 / 27,903 | 12.5 |
| ① | 3 | 50 | 50 | 45.0 | 1,475 / 2,050 | 11.9 |
| A | 3 | 100 | 50 | **46.5** | 11,790 / 21,770 | 11.4 |
| B | 3 | 100 | 200 | **60.2** | 6,325 / 11,842 | 11.6 |
| C | 10 | 200 | 500 | **160.4** | 2,362 / 4,265 | 12.8 |

| 조건 | Outbox 대기 평균 / 최대 | 원장 잠금 대기 평균 / 최대 | 원장 거래 등록 (ms) | 은행 출금 (ms) | 원장 Hikari 대기 / 사용 중 최대 | PC CPU 평균 |
| --- | --- | --- | --- | --- | --- | --- |
| 기준선 | 22 / 99 | 0 / 0 | 8.4 | 6.4 | 0 / 1 | 10.0% |
| ① | 49 / 77 | 0.09 / 1 | 9.5 | 6.4 | 0 / 2 | 11.1% |
| A | **533 / 1,061** | 0.07 / 1 | 9.2 | 6.3 | 0 / 3 | 11.5% |
| B | 35 / 107 | 0.06 / 1 | 8.8 | 6.1 | 0 / 2 | 13.7% |
| C | 114 / 257 | **0.96 / 2** | **14.3** | 8.2 | 0 / 6 | 30.5% |

A·B·C는 3회 중앙값이고, 기준선의 서비스 수치는 `c1-r1`, ①은 `c3-r2` 한 회차 값입니다.

### 정합성

본 측정 15회(충전 30,000건)와 예열·스모크 모두 다음을 만족했습니다.

- 모든 충전 `COMPLETED`, 202 외 응답 0, dropped 0
- 출금 정합성: 측정 계좌의 DEBIT 성공 건수·합계 = 완료 충전 건수·합계, 잔액 감소 = 출금 합계
- 운영 대사 API: 어긋남 0, 판정 보류 0

실제 연동 대사 소요 시간: 1,000건 0.17초, 2,000건 0.31~0.34초, 4,000건 0.73초.

### 분석

**병목이 옮겨 간 순서**

| 단계 | 처리량 | 병목 | 근거 | 다음 조치 |
| --- | --- | --- | --- | --- |
| 기준선 | 21 | 컨슈머 한 스레드 | 넣은 50 > 처리 21, 완료 지연 14초(큐 대기) | 동시성 3 |
| ① | 45 | 넣는 속도 + Outbox | 완료 지연 1.5초로 큐가 거의 안 쌓임. Outbox에 항상 약 1초치(49건) 대기 | 넣는 속도를 올려 확인 |
| A | 46.5 | **Outbox 발행 상한** | 넣는 속도를 2배로 올려도 처리량 그대로, Outbox가 1,000건 넘게 쌓임. 1초마다 최대 50건 발행 | batch-size 200 |
| B | 60.2 | 컨슈머 세 스레드 | Outbox 대기 사라짐. 21 × 3 = 63의 95%로 거의 선형. 원장 거래 등록 시간은 A와 같음(9.2 → 8.8ms) | 동시성 10 |
| C | 160.4 | 뚜렷한 단일 병목 없음 | 21 × 10 = 210의 76%로 효율 저하 시작. 완료 지연 P95 4.3초로 조금 밀림 | 측정 종료 |

**기준선 한 건 48ms의 구성 (1 ÷ 21건/초)**: 은행 6.4ms(13%) + 원장 8.4ms(18%) + 지갑 자체 약 33ms(약 70%). CPU 10%, 커넥션 대기 0이라 자원 부족이 아니라 한 스레드가 단계를 차례로 기다리는 구조의 한계입니다. 지갑 자체 시간은 단계별 상태 전이 커밋, 이력 저장, Feign 왕복, 메시지마다 오프셋 커밋(`AckMode.RECORD`)으로 보이며, 어느 것이 큰지는 이 지표로 구분하지 못했습니다(추정).

**동시성 10의 효율 저하 (스레드당 한 건 48 → 약 62ms, +14.5ms)**: 원장 +5.9ms(약 40%), 지갑 자체 +약 7ms(약 절반), 은행 +1.8ms(약 12%). 평균끼리의 근사입니다. 원장 시스템 계정(`CHARGE_SOURCE`) 행 경합이 시작됐지만(잠금 대기 0 → 약 1), 원장 커넥션 풀은 차지 않았고(대기 0, 사용 중 최대 6/10) PC CPU도 31%였습니다.

**동시성 10을 고른 이유**: 운영 값을 정하려는 것이 아니라 원장 시스템 계정 행이 언제 병목이 되는지 확인하려는 실험값입니다. 원장 커넥션 풀이 10개라 동시에 원장을 부르는 스레드를 10개까지 올리면 풀을 가득 채울 수 있는 조건이 되고, 그 이상은 잠금이 아니라 풀 대기를 재게 되어 원인이 섞입니다.

**송금 hot과의 비교**: 같은 원장, 같은 풀 크기인데 원장 잠금 대기가 송금 hot은 평균 8.3, 충전 동시성 10은 약 1입니다.

| | 송금 hot (동시 20) | 충전 C (동시성 10) |
| --- | --- | --- |
| 요청 한 건 시간 | 약 212ms | 약 62ms |
| 그중 원장 시간 | 166ms (약 78%) | 14.3ms (약 23%) |
| 동시에 원장에 있는 요청 수 (동시 수 × 비율) | 약 16 | 약 2.3 |
| 실측 원장 잠금 대기 평균 | 8.3 | 0.96 |

충전 스레드는 시간의 대부분을 지갑 안과 은행 호출에 쓰고 원장에는 잠깐만 머물러, 같은 행을 동시에 노리는 수가 적습니다. 원장 Hikari 사용 중 평균(약 3)도 이 계산과 맞습니다. 충전에서 원장 핫 계정이 병목이 되려면 동시성이 이보다 훨씬 높아야 할 것으로 봅니다(추정, 미측정).

### 한계

- 노트북 한 대에 모든 서비스를 띄운 측정이라 절대 처리량이 아니라 같은 날 같은 조건의 비교로만 씀
- 조건 C의 정확한 처리 한도는 측정하지 않음(넣은 200건/초보다 약간 아래라는 것만 확인)
- 지갑 내부 시간(기준선의 약 70%)을 단계별로 나누지 못함
- 시간 분해는 평균끼리 더하고 뺀 근사

### 개선 후보 (구현하지 않음)

| 후보 | 근거 | 판단 |
| --- | --- | --- |
| 운영 컨슈머 동시성을 1보다 크게 | 동시성 3까지 거의 선형 확장, 정합성 유지 | 트래픽 규모가 정해지면 결정. 이 측정이 판단 근거 |
| Outbox batch-size·주기 조정 | 기본값(1초 50건)이 처리량 상한이었음 | 동시성을 올릴 때 함께 올려야 함 |
| 지갑 SAGA 내부 시간 분해 | 한 건 시간의 약 70% | 단계별 타이머 지표를 추가해 상태 전이 커밋과 오프셋 커밋 중 큰 쪽을 확인한 뒤 판단 |
| 원장 시스템 계정 경합 대응(계정 분할 등) | 동시성 10에서 경합 시작 | 원장 설계 변경이고 아직 병목이 아니라 보류 |