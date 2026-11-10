# 지갑·충전 실행·사용 가이드

wallet-service는 회원 지갑의 개설·잔액 조회와 원화 충전을 맡습니다. 잔액은 지갑이 가지지 않고 원장(ledger-service)의 계정 잔액을 그대로 보여 줍니다. 충전은 은행 출금 → 원장 적립 순서로 진행하는 SAGA이며, 원장이 적립을 거절하면 은행에 환불합니다. 외부 호출 결과를 모르면 되돌리지 않고 같은 요청 ID로 다시 보냅니다.

## 로컬 실행

필요한 것: JDK 21, Docker.

1. `.env.example`을 `.env`로 복사하고 비밀번호 값을 바꿉니다. `WALLET_ACCOUNT_NUMBER_KEY`는 각자 만듭니다(`openssl rand -base64 32`). 실제 값은 저장소에 올리지 않습니다.
2. DB와 Kafka를 실행합니다.

   ```bash
   docker compose up -d postgres kafka
   ```

3. 충전까지 확인하려면 아래 순서로 실행합니다. 앱 사이에 자동 실행 연결은 없습니다.

   | 순서 | 서비스 | 포트 | 지갑에서 쓰는 곳 |
   |---|---|---|---|
   | 1 | postgres | `.env`의 `POSTGRES_PORT` | `wallet_service` 스키마 |
   | 1 | kafka | 9092 | 충전 이벤트 `wallet.charge.requested` |
   | 2 | eureka-server | 8761 | 지갑 등록, user·ledger 찾기 |
   | 3 | user-service | 8081 | 충전 예금주 확인용 실명 조회 |
   | 4 | ledger-service | 8085 | 지갑 계정 개설·잔액, 충전 적립 |
   | 5 | mock-bank | 8090 | 충전 출금·환불 |
   | 6 | wallet-service | 8082 | |
   | 7 | gateway | 8080 | 브라우저 요청을 보낼 때만 |

4. Spring Boot는 `.env`를 직접 읽지 않으므로 환경 변수로 넘겨 실행합니다. Mock Bank는 Eureka에 등록하지 않으므로 `MOCK_BANK_URL`이 필요합니다.

   ```bash
   set -a; source .env; set +a
   MOCK_BANK_URL=http://localhost:8090 ./gradlew :wallet-service:bootRun
   ```

   - `GET http://localhost:8082/actuator/health`로 상태를 확인합니다.

### 환경 변수

| 변수 | 필수 | 기본값 | 없을 때 |
|---|---|---|---|
| `WALLET_DB_URL`·`WALLET_DB_USERNAME`·`WALLET_DB_PASSWORD` | 예 | 없음 | 기동 실패. URL은 `currentSchema=wallet_service`를 붙입니다(`.env.example` 참고) |
| `WALLET_ACCOUNT_NUMBER_KEY` | 예 | 없음 | 기동 실패. 계좌번호 AES-256-GCM 키(Base64, 32바이트) |
| `MOCK_BANK_URL` | 로컬 충전 시 예 | 빈 값(Eureka에서 `mock-bank` 찾음) | Mock Bank가 Eureka에 없어 은행 호출이 실패합니다. 충전이 `BANK_PROCESSING`에서 결과 모름으로 쌓이다가 `MANUAL_REVIEW`가 됩니다 |
| `LEDGER_SERVICE_URL` | 아니오 | 빈 값(Eureka에서 `ledger-service` 찾음) | |
| `USER_SERVICE_URL` | 아니오 | 빈 값(Eureka에서 `user-service` 찾음) | |
| `KAFKA_BOOTSTRAP_SERVERS` | 아니오 | `localhost:9092` | |
| `BANK_FAULT_INJECTION_MODE` | 아니오 | 빈 값(끔) | [장애 주입](#장애-주입) |

- Kafka가 꺼져 있으면 Outbox 발행이 실패해 WARN이 반복됩니다(발행 한 건이 최대 3초 멈춤). 충전 접수는 되고, `REQUESTED`로 30초 넘게 머문 충전은 [복구 스케줄러](#복구-스케줄러)가 Kafka 없이 직접 실행합니다.
- 배포 설정(`application-prod.yml`)은 `SPRING_CONFIG_NAME=application-prod`로 선택합니다. 배포 포트는 `8080`, Eureka 주소는 `http://eureka-server:8761/eureka/`이고, 은행·원장·회원 주소를 비워 모두 Eureka로 찾습니다. `KAFKA_BOOTSTRAP_SERVERS`는 기본값이 없어 필수입니다. 장애 주입 키는 없습니다.
- compose에는 wallet-service 컨테이너가 없습니다. 로컬에서는 `bootRun`으로 실행합니다.
- 테이블은 앱이 시작할 때 Flyway가 만들고([DB 스키마](#db-스키마-flyway)), Kafka 토픽은 앱이 선언합니다(브로커 자동 생성 꺼짐).

## Swagger

로컬에서만 열립니다. prod 프로필(`application-prod.yml`)에서는 API 문서와 Swagger UI가 모두 꺼집니다.

| 항목 | 값 |
|---|---|
| Swagger UI | `http://localhost:8082/swagger-ui.html` |
| 그룹 `wallet` (지갑·충전) | 회원용 `/api/v1/**` (관리자 경로 제외). 명세 `/v3/api-docs/wallet` |
| 그룹 `charge-admin` (충전 관리) | `/api/v1/admin/charges/**` (대사·보정). 명세 `/v3/api-docs/charge-admin` |

- 내부 API(`/internal/**`)는 문서에 없습니다.
- 지갑을 직접 호출하므로 Gateway가 넣어 주는 헤더를 직접 넣습니다. 회원 API는 `User-Id`(회원 UUID)·`User-Role: USER`, 관리자 API는 `User-Role: MASTER`입니다. MASTER가 아니면 관리자 API는 403입니다.
- 충전 요청(`POST /api/v1/charges`)과 보정(`POST /api/v1/admin/charges/{chargeId}/resolve`)은 `Idempotency-Key`(UUID)가 필수입니다. 같은 키로 다시 보내면 같은 결과를 돌려줍니다.
- 처음에는 입력칸이 잠겨 있습니다. **Try it out**을 누른 뒤 값을 넣고 **Execute**로 요청합니다.
- API의 세부 계약(오류 코드, 멱등 규칙, 상태 전이)은 이 문서를 기준으로 합니다. Swagger는 요청·응답 형식을 확인하고 직접 호출해 보는 용도입니다.

## API 목록

| API | 권한 | 설명 | 성공 | 멱등성 키 |
|---|---|---|---|---|
| `POST /api/v1/wallets` | 회원 | 지갑 개설 | 201(이번 요청으로 활성화)·200(이미 `ACTIVE`) | 없음(회원당 1개라 재요청이 멱등) |
| `GET /api/v1/wallets/me` | 회원 | 내 지갑 | 200 | |
| `GET /api/v1/wallets/me/balances` | 회원 | 내 통화별 잔액 | 200 | |
| `POST /api/v1/charges` | 회원 | 충전 요청 | 202(처리 중)·200(같은 키로 이미 끝난 충전) | `Idempotency-Key` 헤더 필수 |
| `GET /api/v1/charges/{chargeId}` | 회원 | 내 충전 단건 | 200 | |
| `GET /api/v1/charges` | 회원 | 내 충전 목록 | 200 | |
| `GET /api/v1/admin/charges` | 관리자(`MASTER`) | 전체 충전 목록 | 200 | |
| `GET /internal/v1/wallets?userId=` | 내부 | 지갑 조회(송금 파트 계약) | 200 | |
| `GET /internal/v1/wallets/balances?userId=` | 내부 | 통화별 잔액(환전 파트 계약) | 200 | |

- 회원 API를 `MASTER`가 부르면 403 `COMMON_FORBIDDEN`, 관리자 API를 `MASTER`가 아닌 사용자가 부르면 403입니다.
- Gateway 라우트는 `/api/v1/wallets/**`, `/api/v1/charges/**`, `/api/v1/admin/charges/**`입니다. `/internal/**`은 Gateway에 없어 서비스끼리만 부릅니다.
- 요청자는 `User-Id`·`User-Role` 헤더로 받습니다(Gateway가 넣음). 지갑(8082)을 직접 호출할 때는 두 헤더를 직접 붙입니다. 없으면 401 `COMMON_UNAUTHORIZED`.
- 응답 본문은 `success`·`data`·`error` 공통 형식입니다([common 가이드](common.md#공통-응답오류)). 금액은 통화별 최소 단위 정수, 시각은 UTC ISO-8601입니다.

## 지갑

### 개설

`POST /api/v1/wallets` (본문 없음)

```json
{
  "success": true,
  "data": {"walletId": "…", "status": "ACTIVE", "currencies": ["KRW", "JPY", "CNY"]},
  "error": null
}
```

원장 호출이 DB 트랜잭션 안에 들어가지 않도록 세 단계로 나눕니다.

1. 지갑을 `PENDING`으로 추가합니다(이미 있으면 그대로). 이미 `ACTIVE`면 바로 200으로 끝납니다.
2. 트랜잭션 밖에서 원장에 지갑 계정 개설을 요청합니다(`POST /internal/v1/ledger/accounts`). 원장이 KRW·JPY·CNY 계정을 만들어 돌려줍니다.
3. 지갑 행을 잠그고 통화별 행(`wallet_currencies`)을 원장 계정 ID와 함께 추가한 뒤, 세 통화가 모두 원장 계정과 맞으면 `ACTIVE`로 바꿉니다.

- 원장 장애로 2단계에서 멈추면 지갑은 `PENDING`으로 남습니다. 같은 요청을 다시 보내면 빠진 단계만 수행해 `ACTIVE`가 됩니다(원장 계정 개설도 멱등).
- 같은 회원의 동시 요청은 한 요청만 활성화(201)하고 나머지는 이미 `ACTIVE`로 봅니다(200).
- 원장 계정 ID는 응답에 넣지 않습니다.

### 상태

| 상태 | 의미 |
|---|---|
| `PENDING` | 원장 계정 개설 전. 개설 재요청으로 `ACTIVE`가 됨 |
| `ACTIVE` | 사용 가능 |
| `SUSPENDED`·`CLOSED` | 값만 있고 바꾸는 API는 없음. 개설·잔액 조회·충전 시 409 `WALLET_NOT_ACTIVE` |

통화별 행에도 상태(`ACTIVE`·`SUSPENDED`·`CLOSED`, 기본 `ACTIVE`)가 있지만 바꾸는 API는 없습니다.

### 조회

- `GET /api/v1/wallets/me`: `{walletId, status, currencies}`. 지갑이 없으면 404 `WALLET_NOT_FOUND`. 상태와 관계없이 돌려줍니다.
- `GET /api/v1/wallets/me/balances`: `[{currency, balance}]`. 원장 잔액을 캐시 없이 그대로 돌려줍니다. 지갑이 없으면 404, `ACTIVE`가 아니면 409. 원장 장애면 503 `WALLET_LEDGER_UNAVAILABLE`.
- 내부 `GET /internal/v1/wallets?userId=`: `{walletId, status}`. 지갑 없으면 404 `WALLET_NOT_FOUND`. 필드와 404는 송금 파트와의 계약입니다.
- 내부 `GET /internal/v1/wallets/balances?userId=`: `{walletId, status, balances: [{currency, balance, status}]}`. 지갑 상태로 막지 않고 응답에 담습니다. 통화 행이 없는(`PENDING`) 지갑은 `balances`가 빈 배열입니다. 필드와 404는 환전 파트와의 계약입니다.
- 지갑에 통화 행이 있는데 원장 계정이 없으면 ERROR 로그와 500입니다.

## 충전 흐름

원화(KRW)만 충전할 수 있습니다(원장 충전 원천 계정이 KRW뿐).

### 요청

```http
POST /api/v1/charges
Idempotency-Key: 3f0c6a1e-8a52-4d0b-9b7e-2f0b7a1c9d11
Content-Type: application/json

{"amount": 10000, "currency": "KRW", "bankCode": "004", "accountNumber": "12340101234567"}
```

| 항목 | 규칙 |
|---|---|
| `Idempotency-Key` | 필수. 표준 UUID 표기만(축약형 거부). 없거나 형식이 틀리면 DB 조회 전에 400 `CHARGE_INVALID_IDEMPOTENCY_KEY` |
| `amount` | 필수, 0보다 큰 정수 |
| `currency` | 필수. `KRW`가 아니면 400 `CHARGE_CURRENCY_NOT_SUPPORTED` |
| `bankCode` | 필수, 숫자 3자리 |
| `accountNumber` | 필수, 숫자 10~20자리 |

본문 검증 실패는 400 `COMMON_INVALID_REQUEST`입니다.

### 응답

```json
{"success": true, "data": {"chargeId": "…", "status": "REQUESTED"}, "error": null}
```

- 처음 요청은 접수만 하고 202 `REQUESTED`로 응답합니다. 결과는 [충전 조회](#충전-내역-조회)로 확인합니다.
- 같은 회원·같은 키 재요청은 지갑 확인·외부 호출보다 먼저 기존 충전을 찾습니다. 내용(금액·통화·은행 코드·계좌번호)이 같으면 기존 `chargeId`와 현재 상태를 돌려줍니다(처리 중이면 202, 끝났으면 200). 다르면 409 `CHARGE_IDEMPOTENCY_CONFLICT`.
- 같은 키 동시 요청은 하나만 저장되고(`UNIQUE(user_id, idempotency_key)`) 나머지는 위 규칙으로 응답합니다.

### 접수

1. 지갑이 `ACTIVE`인지 확인합니다(없으면 404, 아니면 409).
2. 트랜잭션 밖에서 회원 서비스에 실명을 조회합니다. 실패하면 아무것도 저장하지 않습니다([다른 서비스 호출](#다른-서비스-호출)).
3. 계좌번호를 AES-256-GCM으로 암호화합니다. 평문·마스킹 값은 저장하지 않습니다.
4. 한 트랜잭션으로 저장합니다: 충전(`REQUESTED`) + 상태 이력(`null → REQUESTED`) + Outbox 이벤트. 이때 은행 출금 요청 ID(`bank_request_id`)와 환불 요청 ID(`refund_request_id`)를 만들어 고정합니다.

### Outbox → Kafka → SAGA

| 단계 | 내용 |
|---|---|
| Outbox 발행기 | 1초 간격(이전 주기 종료 후), 한 번에 50건. `PENDING`을 `retry_count`·`created_at` 순으로 `FOR UPDATE SKIP LOCKED`로 잠그고 보냅니다. 성공하면 `PUBLISHED`, 실패하면 `retry_count + 1` |
| 토픽 | `wallet.charge.requested`(파티션 3). 키 `chargeId`, 헤더 `eventId`(Outbox ID). 값은 `chargeId`·`walletId`·`userId`·`amount`·`currency`·`bankCode`·`bankRequestId`(계좌번호 없음) |
| 컨슈머 | 그룹 `wallet-charge-saga`, 동시성 1, 레코드 단위 커밋, `auto-offset-reset: earliest`. `chargeId`로 SAGA 실행기를 부릅니다 |
| 컨슈머 오류 | DB 장애 같은 인프라 예외만 1초 간격 2회 다시 실행하고, 그래도 실패하면 ERROR 후 넘깁니다(멈춘 건은 복구 스케줄러가 처리). 메시지 형식 이상·허용되지 않은 전이는 바로 넘깁니다 |

같은 이벤트를 다시 받아도 실행기가 충전 상태를 보고 판단하므로(종료 상태면 아무것도 안 함) 이벤트 중복 제거는 하지 않습니다. `eventId`는 상태 이력 `trace_id`에 남깁니다.

### SAGA 단계

실행기는 현재 상태의 단계를 실행하고 성공하면 다음 단계를 이어서 실행합니다. 은행·원장 호출은 트랜잭션 밖에서 하고, 상태 변경만 짧은 트랜잭션(3초)으로 합니다. 상태 변경은 `WHERE id = ? AND status = ?` 조건부 UPDATE이며, 0행이면(다른 실행기가 먼저 진행) 외부 호출 없이 멈춥니다.

| 현재 상태 | 하는 일 | 결과 → 다음 상태 |
|---|---|---|
| `REQUESTED` | 없음 | → `BANK_PROCESSING` |
| `BANK_PROCESSING` | 은행 출금 (`bank_request_id`, 저장된 실명을 예금주로 보냄) | 성공 → `CREDIT_PROCESSING`(`bank_tx_id` 저장) / 업무 거절 → `FAILED` / 결과 모름 → 상태 유지 |
| `CREDIT_PROCESSING` | 원장 적립 | 반영 → `COMPLETED`(`ledger_ref` 저장) / 422 `LEDGER_REJECTED_*` → `REFUND_PROCESSING` / 결과 모름 → 상태 유지 |
| `REFUND_PROCESSING` | 은행 환불 (`refund_request_id`, 원 출금 `bank_tx_id`, 전액) | 성공 → `REFUNDED`(`refund_bank_tx_id` 저장) / 업무 거절 → `MANUAL_REVIEW` / 결과 모름 → 상태 유지 |

모든 처리 중 상태에서 응답 이상은 `MANUAL_REVIEW`로 갑니다([결과 모름과 복구](#결과-모름과-복구)).

### 상태 값과 전이

| 상태 | 화면 상태(`ChargeDisplayStatus`) | 갈 수 있는 상태 |
|---|---|---|
| `REQUESTED` | `PROCESSING` | `BANK_PROCESSING` |
| `BANK_PROCESSING` | `PROCESSING` | `CREDIT_PROCESSING`, `FAILED`, `MANUAL_REVIEW` |
| `CREDIT_PROCESSING` | `PROCESSING` | `COMPLETED`, `REFUND_PROCESSING`, `MANUAL_REVIEW` |
| `REFUND_PROCESSING` | `PROCESSING` | `REFUNDED`, `MANUAL_REVIEW` |
| `COMPLETED` | `COMPLETED` | 없음(종료) |
| `FAILED` | `FAILED` | 없음(종료) |
| `REFUNDED` | `REFUNDED` | 없음(종료) |
| `MANUAL_REVIEW` | `REVIEWING` | 없음(종료) |

- 전이표(`ChargeStatus.nextStatuses`)에 없는 전이는 DB를 건드리기 전에 막습니다.
- 전이할 때마다 `charge_status_history`에 한 행을 남깁니다(`from_status`, `to_status`, `reason_code`=실패 코드, `reason_detail`=last_error, `trace_id`). 결과 모름 기록은 상태가 바뀌지 않아 이력을 남기지 않습니다.
- 다음 단계로 전이하면 `attempt_count`를 0으로 되돌립니다(결과 모름 한도는 단계별).
- 조회 응답의 `status`는 내부 상태 8개를 그대로 보냅니다. 화면 상태 5개는 코드에만 있고 응답 필드는 없습니다.

### 실패 코드

| `failureCode` | 상황 | 상태 |
|---|---|---|
| `BANK_INSUFFICIENT_BALANCE` | 은행 `INSUFFICIENT_BALANCE` | `FAILED` |
| `BANK_ACCOUNT_SUSPENDED` | 은행 `ACCOUNT_SUSPENDED` | `FAILED` |
| `BANK_ACCOUNT_NOT_FOUND` | 은행 `ACCOUNT_NOT_FOUND` | `FAILED` |
| `BANK_HOLDER_MISMATCH` | 은행 `HOLDER_MISMATCH` | `FAILED` |
| `BANK_REJECTED` | 위에 없는 은행 출금 거절. 원래 코드는 `last_error`에 | `FAILED` |
| `LEDGER_REJECTED` | 원장 422 `LEDGER_REJECTED_*`. 원장 코드는 `last_error`에 | `REFUND_PROCESSING` → `REFUNDED` |
| `RETRY_EXHAUSTED` | 결과 모름 5회 | `MANUAL_REVIEW` |
| `RESPONSE_INVALID` | 은행·원장 응답이 409·400·형식 이상 | `MANUAL_REVIEW` |
| `ACCOUNT_DECRYPT_FAILED` | 저장된 계좌번호 복호화 실패 | `MANUAL_REVIEW` |

- 환불이 은행에서 거절되면 `failureCode`는 `LEDGER_REJECTED`를 유지하고 `last_error`에 `refund failureCode=…`를 남긴 채 `MANUAL_REVIEW`가 됩니다.
- 응답의 `failureMessage`는 코드별 사용자용 문구입니다. `last_error`는 응답에 넣지 않습니다.

### 예금주 확인

- 접수 때 회원 서비스의 실명을 `holder_name_snapshot`에 저장하고, 출금 요청의 예금주로 보냅니다.
- 비교는 Mock Bank가 합니다. 계좌의 `holder_name`과 요청 예금주(앞뒤 공백 제거)가 정확히 같아야 하며, 다르면 업무 거절 `HOLDER_MISMATCH` → 충전 `FAILED`(`BANK_HOLDER_MISMATCH`).

### 원장 분개

`referenceType: CHARGE`, `referenceId: chargeId`로 등록합니다.

| 차변(DEBIT) | 대변(CREDIT) |
|---|---|
| `SYSTEM CHARGE_SOURCE` KRW, `CHARGE_OUT` | `USER` 회원 지갑 KRW, `CHARGE_IN` |

reference가 원장의 멱등 키라 같은 충전을 다시 보내도 한 번만 반영됩니다([원장 가이드](ledger-service.md#멱등성)).

## 결과 모름과 복구

### 응답 분류

| 응답 | 은행 출금·환불 | 원장 적립 |
|---|---|---|
| 2xx, 내용 정상 | 성공(`SUCCEEDED` + `bankTxId`) 또는 업무 거절(`FAILED`) | 반영(`transactionId` + `POSTED`) |
| 2xx, 내용 이상(`requestId`·유형 불일치, 필드 누락 등) | `MANUAL_REVIEW`(`RESPONSE_INVALID`) | `MANUAL_REVIEW`(`RESPONSE_INVALID`) |
| 422 + `LEDGER_REJECTED_*` | — | 환불 진행 |
| 그 밖의 4xx(409·400 등) | `MANUAL_REVIEW`(`RESPONSE_INVALID`) | `MANUAL_REVIEW`(`RESPONSE_INVALID`) |
| 5xx·읽기 타임아웃·연결 실패·3xx·2xx 본문 해석 실패·요청 전송 전 실패 | 결과 모름 | 결과 모름 |

- 결과 모름: 상태를 유지하고 `attempt_count + 1`, `last_error`에 예외 클래스명을 남긴 뒤 멈춥니다. 같은 단계에서 5회가 되면 같은 트랜잭션에서 `MANUAL_REVIEW`(`RETRY_EXHAUSTED`)로 바꿉니다.
- 보상(환불)하지 않는 이유: 5xx·타임아웃이어도 상대가 이미 처리했을 수 있습니다. 대신 접수 때 고정한 `bank_request_id`·`refund_request_id`와 원장 reference(`chargeId`)로 같은 요청을 다시 보내 한 번만 반영되게 합니다. Mock Bank는 같은 `requestId` 재요청에 기존 결과를 200으로 돌려줍니다.
- 원장 409를 환불하지 않는 이유: 같은 reference 거래가 이미 있다는 뜻이라 환불하면 이중 지급 위험이 있습니다.
- Feign 자동 재시도는 끕니다. 재시도는 SAGA가 같은 요청 ID로 다시 보내는 방식으로만 합니다.

### 복구 스케줄러

처리 중 상태로 멈춘 충전을 찾아 SAGA 실행기를 다시 돌립니다. Kafka가 꺼져 이벤트가 오지 않은 `REQUESTED`도 대상입니다.

| 항목 | 값 |
|---|---|
| 주기 | `travelpay.wallet.charge.recovery.fixed-delay` 기본 `10s`(이전 주기 종료 후). 1초 미만이거나 형식이 틀리면 기동 실패 |
| 건수 | `travelpay.wallet.charge.recovery.batch-size` 기본 `10`. 1 미만이면 기동 실패 |
| 켜기·끄기 | `travelpay.wallet.charge.recovery.enabled` 기본 `true`(테스트용 끔) |
| 대상 | `REQUESTED`·`BANK_PROCESSING`·`CREDIT_PROCESSING`·`REFUND_PROCESSING` 중 대기 시간이 지난 건, `updated_at` 오래된 순 |
| 대기 시간 | `30초 × 2^min(max(attempt_count − 1, 0), 5)`, DB `now()` 기준 |
| 선점 | 건마다 짧은 트랜잭션으로 `updated_at = now()` 조건부 UPDATE(`status`·`updated_at`이 조회 때와 같을 때만). 실패하면 건너뜀 |
| 실행 | 선점 커밋 후 트랜잭션 밖에서 실행기 호출. 이력 `trace_id`는 `recovery-{UUID}` |

| `attempt_count` | 0 | 1 | 2 | 3 | 4 |
|---|---|---|---|---|---|
| 다음 실행까지 | 30초 | 30초 | 60초 | 120초 | 240초 |

- 5회째 결과 모름이면 `MANUAL_REVIEW`가 되므로 처리 중 상태의 `attempt_count`는 최대 4입니다.
- 한 건의 예외가 같은 배치의 다른 건을 멈추지 않습니다. 배치마다 `found`·`claimed`·`claimMissed`·`failed`·`elapsedMs`를 INFO로 남깁니다.
- 스케줄러 스레드 풀은 2입니다(`spring.task.scheduling.pool.size`). 복구 배치가 길어도 Outbox 발행이 막히지 않게 하기 위해서입니다.
- 컨슈머와 스케줄러가 같은 충전을 동시에 실행해도 조건부 UPDATE로 한쪽만 전이합니다.

### MANUAL_REVIEW로 가는 경우

- 같은 단계에서 결과 모름 5회(`RETRY_EXHAUSTED`)
- 은행·원장 응답이 409·400·422(원장 `LEDGER_REJECTED_*` 제외)·내용 이상(`RESPONSE_INVALID`)
- 계좌번호 복호화 실패(`ACCOUNT_DECRYPT_FAILED`). 이전 시도에서 이미 출금됐을 수 있어 실패로 끝내지 않습니다
- 환불 업무 거절(`failureCode`는 `LEDGER_REJECTED` 유지)

`MANUAL_REVIEW`는 종료 상태이며 푸는 API는 아직 없습니다([고려 중](#고려-중)).

## 장애 주입

은행 응답 유실을 로컬에서 재현합니다.

| 항목 | 내용 |
|---|---|
| 설정 키 | `travelpay.client.bank.fault-injection.mode` (환경 변수 `BANK_FAULT_INJECTION_MODE`) |
| 모드 | 빈 값(끔), `LOST`, `DELAY`. 대소문자·앞뒤 공백 무시. 그 외 값은 기동 실패 |
| 적용 범위 | 은행 **출금 첫 시도**(`attempt_count = 0`)에만 `Mock-Fault` 헤더를 붙입니다. 재시도에도 붙이면 매번 유실되어 `MANUAL_REVIEW`로 끝나기 때문입니다. 환불에는 붙이지 않습니다 |
| prod 보호 | `prod` 프로필이 켜져 있을 때 값이 있으면 기동 실패. 배포 설정 파일에는 이 키가 없습니다 |
| Mock 은행 조건 | 지갑은 헤더만 붙이고, 실제 장애는 Mock 은행이 만듭니다. Mock 은행을 `--mock-bank.fault.enabled=true`(prod 아님)로 띄워야 합니다. 꺼져 있으면 헤더를 무시하고 정상 응답하므로 장애가 재현되지 않습니다([Mock 은행 가이드](mock-bank.md#장애-재현)) |

Mock Bank 동작(POST에 `Mock-Fault` 헤더가 있을 때, 처리·커밋이 끝난 뒤 적용):

| 모드 | Mock Bank 응답 | 지갑이 보는 결과 |
|---|---|---|
| `LOST` | 원래 응답을 버리고 500 `COMMON_INTERNAL_SERVER_ERROR` | 결과 모름 |
| `DELAY` | `mock-bank.fault.delay`(기본 10초) 뒤 원래 응답 | 읽기 타임아웃(5초) → 결과 모름 |

### 재현 방법

1. Mock 은행을 `--mock-bank.fault.enabled=true`로 띄우고, 지갑을 `BANK_FAULT_INJECTION_MODE=LOST`(또는 `DELAY`)로 실행합니다.

   ```bash
   set -a; source .env; set +a
   MOCK_BANK_URL=http://localhost:8090 BANK_FAULT_INJECTION_MODE=LOST ./gradlew :wallet-service:bootRun
   ```

2. [정상 계좌](#테스트-데이터)로 충전을 요청합니다. 지갑 로그에 `은행 장애 주입 헤더 추가`(WARN), `충전 단계 결과 모름`(WARN)이 남습니다.
3. 충전 조회 결과는 `BANK_PROCESSING`이고 관리자 조회의 `attemptCount`는 1입니다. Mock Bank에서는 이미 출금되었습니다.
4. 약 30초 뒤 복구 스케줄러가 같은 `bank_request_id`로 헤더 없이 다시 보냅니다. Mock Bank가 기존 성공 결과를 돌려주고 충전이 `CREDIT_PROCESSING` → `COMPLETED`가 됩니다.
5. 확인할 것: Mock Bank 계좌 잔액이 한 번만 줄었는지, 원장 잔액이 한 번만 늘었는지, 상태 이력이 `null→REQUESTED→BANK_PROCESSING→CREDIT_PROCESSING→COMPLETED`인지.

## 충전 내역 조회

### 단건

`GET /api/v1/charges/{chargeId}`

```json
{
  "success": true,
  "data": {
    "chargeId": "…",
    "status": "COMPLETED",
    "amount": 10000,
    "currency": "KRW",
    "bankCode": "004",
    "accountNumberMask": "**********4567",
    "failureCode": null,
    "failureMessage": null,
    "createdAt": "2026-11-05T01:02:03.123456Z",
    "updatedAt": "2026-11-05T01:02:04.654321Z"
  },
  "error": null
}
```

- 본인 충전만 봅니다. 다른 회원의 충전과 없는 충전은 같은 404 `CHARGE_NOT_FOUND`입니다.
- 계좌번호는 뒤 4자리만 남긴 마스킹 값입니다. 원문·실명은 응답에 없습니다.

### 목록

| 항목 | 회원 `GET /api/v1/charges` | 관리자 `GET /api/v1/admin/charges` |
|---|---|---|
| 권한 | `MASTER` 아닌 회원, 본인 충전만 | `MASTER`만 |
| 필터 | `status`, `from`, `to` | `userId`, `status`, `from`, `to` |
| 항목 | 단건과 같음 | 단건 항목 + `userId`, `attemptCount` |

- 모든 필터는 선택입니다. `status`는 내부 상태 8개 중 하나입니다.
- 정렬은 `createdAt` 내림차순, 같으면 `chargeId` 내림차순으로 고정입니다. 정렬 파라미터는 받지 않습니다.
- 페이지: `page`는 0부터(기본 0), `size` 기본 20, 100을 넘으면 100으로 조회합니다.
- 응답 `data`: `items`, `page`, `size`, `totalElements`, `totalPages`.
- 기간은 `createdAt` 기준 `[from, to)`입니다. ISO-8601 날짜·시각(`2026-11-05T00:00:00Z`)으로 보냅니다. URL 쿼리의 `+` 오프셋은 `%2B`로 인코딩합니다.
- DB 시각 정밀도는 마이크로초라 `from`·`to`의 마이크로초 미만 값은 다음 마이크로초로 올려 조회합니다(`[from, to)` 계약은 그대로). 올림이 범위를 넘으면 400.
- 400 `COMMON_INVALID_REQUEST`: `page < 0`, `size < 1`, `from`이 `to`보다 늦음, `page × size`(보정된 size 기준)가 int 범위를 넘음.
- 인덱스 `ix_charge_requests_user_created(user_id, created_at DESC)`를 씁니다. 회원 목록은 `userId` 조건을 항상 겁니다.

## 테스트 데이터

### 회원 실명 조건

충전 출금은 회원 실명과 Mock Bank 계좌 예금주가 정확히 같아야 성공합니다. 아래 시드 계좌로 충전하려면 회원 가입 때 실명을 계좌의 예금주와 같게 넣습니다(예: `유재석`). 회원은 `ACTIVE`여야 합니다(정지·해지면 409 `CHARGE_USER_NOT_ACTIVE`).

### Mock Bank 시드 계좌 (`V3__seed_mock_bank_accounts.sql`)

| 은행 코드 | 계좌번호 | 예금주 | 잔액 | 상태 | 충전 결과 |
|---|---|---|---|---|---|
| `004` | `12340101234567` | 유재석 | 3,000,000 | `ACTIVE` | 성공 |
| `088` | `110123456789` | 박지현 | 1,500,000 | `ACTIVE` | 성공 |
| `020` | `1002123456789` | 유재석 | 5,000 | `ACTIVE` | 5,000원 초과 충전은 `BANK_INSUFFICIENT_BALANCE` |
| `011` | `3021234567891` | 유재석 | 2,000,000 | `SUSPENDED` | `BANK_ACCOUNT_SUSPENDED` |

- 플랫폼 계좌 `004`/`99010100000001`(`(주)트래블페이`)는 출금이 들어오고 환불이 나가는 계좌입니다. 충전 계좌로 쓰면 `ACCOUNT_NOT_FOUND`입니다(회원 계좌만 찾음).
- 시드에 없는 계좌는 `BANK_ACCOUNT_NOT_FOUND`, 예금주와 실명이 다르면 `BANK_HOLDER_MISMATCH`입니다.
- Mock Bank 판정 순서: 계좌 없음 → 정지 → 예금주 불일치 → 잔액 부족.
- 출금 성공은 회원 계좌 잔액을 줄이고 플랫폼 계좌 잔액을 늘립니다.

## 다른 서비스 호출

| 대상 | API | 쓰는 곳 |
|---|---|---|
| user-service | `GET /internal/v1/users/{userId}/funding-identity` (`User-Id`=회원, `User-Role`=`USER`) | 충전 접수 |
| ledger-service | `POST /internal/v1/ledger/accounts`, `GET /internal/v1/ledger/accounts?walletId=` | 지갑 개설, 잔액 조회 |
| ledger-service | `POST /internal/v1/ledger/transactions` | 충전 적립 |
| mock-bank | `POST /internal/v1/bank/withdrawals`, `POST /internal/v1/bank/refunds` | 충전 출금, 환불 |

### 실패 시 응답 코드

**회원 실명 조회(충전 접수, 아무것도 저장하지 않음)**

| 회원 서비스 응답 | 지갑 응답 |
|---|---|
| 403 `USER_ACCOUNT_SUSPENDED`·`USER_ACCOUNT_CLOSED`, 404 `USER_NOT_FOUND` | 409 `CHARGE_USER_NOT_ACTIVE` |
| 읽기 타임아웃, 504 | 504 `COMMON_GATEWAY_TIMEOUT` |
| 연결 실패, 503 | 503 `COMMON_SERVICE_UNAVAILABLE` |
| 429 + `COMMON_TOO_MANY_REQUESTS` | 429 |
| 그 밖의 실패(다른 4xx, 그 밖의 5xx, 2xx 본문 해석 실패) | 502 `COMMON_BAD_GATEWAY`. 처리하지 않은 4xx·본문 해석 실패는 ERROR |
| 2xx인데 다른 회원의 응답, 실명 없음·100자 초과 | 500 `COMMON_INTERNAL_SERVER_ERROR`, ERROR |

**원장 계정(지갑 개설·잔액 조회)**

| 원장 응답 | 지갑 응답 |
|---|---|
| 읽기 타임아웃(2xx 본문 읽기 중 포함), 5xx, 연결 실패 | 503 `WALLET_LEDGER_UNAVAILABLE`, WARN |
| 404 `LEDGER_ACCOUNT_NOT_FOUND`(조회만) | 계정 없음으로 처리. `ACTIVE` 지갑이면 ERROR와 500 |
| 2xx인데 세 통화가 하나씩 모두 있지 않음, 필드 누락 | 500, ERROR |
| 그 밖의 실패(4xx, 2xx 본문 해석 실패) | 공통 변환(위 표와 같은 규칙, 대부분 502), ERROR |

**원장 거래·은행(충전 SAGA)**: HTTP 응답으로 돌려주지 않고 충전 상태로 남깁니다([결과 모름과 복구](#응답-분류)).

### Feign 설정

| 클라이언트(설정 키) | connect-timeout | read-timeout | 비고 |
|---|---|---|---|
| `ledger-service` | 1초 | 6초 | 원장 최대 응답(약 5초)보다 길게 |
| `ledger-transaction` | 1초 | 6초 | 원장 거래 등록. `contextId`로 설정 키가 나뉨 |
| `mock-bank` | 1초 | 5초 | 초과하면 결과 모름 |
| `user-service` | 1초 | 2초 | |

- 네 클라이언트 모두 Feign 자동 재시도를 끕니다(`Retryer.NEVER_RETRY`).
- 주소는 `travelpay.client.{bank|ledger|user}.url`이며 비우면 Eureka에서 서비스 이름으로 찾습니다.
- 서비스 간 내부 인증 헤더는 아직 없습니다(코드 TODO).

## 오류 응답

| 상태 | 코드 | 상황 |
|---|---|---|
| 400 | `COMMON_INVALID_REQUEST` | 본문 검증 실패, 목록 `page`·`size`·기간 오류 |
| 400 | `CHARGE_INVALID_IDEMPOTENCY_KEY` | `Idempotency-Key` 없음·표준 UUID 아님 |
| 400 | `CHARGE_CURRENCY_NOT_SUPPORTED` | `KRW` 외 통화 충전 |
| 401 | `COMMON_UNAUTHORIZED` | 요청자 헤더 없음 |
| 403 | `COMMON_FORBIDDEN` | 회원 API를 `MASTER`가, 관리자 API를 `MASTER` 아닌 사용자가 호출 |
| 404 | `WALLET_NOT_FOUND` | 지갑 없음 |
| 404 | `CHARGE_NOT_FOUND` | 충전 없음 또는 다른 회원의 충전 |
| 409 | `WALLET_NOT_ACTIVE` | 지갑이 `SUSPENDED`·`CLOSED`(개설), `ACTIVE` 아님(잔액 조회·충전) |
| 409 | `CHARGE_IDEMPOTENCY_CONFLICT` | 같은 키로 다른 내용의 충전 |
| 409 | `CHARGE_USER_NOT_ACTIVE` | 회원 정지·해지·없음 |
| 500 | `COMMON_INTERNAL_SERVER_ERROR` | 예상하지 못한 오류, 회원·원장 2xx 응답 내용 이상, 지갑·원장 계정 불일치 |
| 502 | `COMMON_BAD_GATEWAY` | 회원·원장 응답을 처리할 수 없음 |
| 503 | `WALLET_LEDGER_UNAVAILABLE` | 원장 타임아웃·5xx·연결 실패(지갑 개설·잔액 조회) |
| 503 | `COMMON_SERVICE_UNAVAILABLE` | DB 연결 불가·커넥션 획득 실패·쿼리/트랜잭션 시간 초과, 회원 서비스 연결 실패·503 |
| 504 | `COMMON_GATEWAY_TIMEOUT` | 회원 서비스 타임아웃·504 |

- 404는 경로 없음(`COMMON_NOT_FOUND`)과 구분하려고 지갑 전용 코드를 씁니다.
- DB 장애 503은 `WalletDatabaseExceptionHandler`가 임시로 맡고 있습니다(추후 common 처리기로 이전 예정). 503은 결과 모름이므로 같은 요청(충전은 같은 `Idempotency-Key`)으로 다시 보냅니다.
- DB 트랜잭션 제한 시간은 3초(Outbox 발행만 10초), Hikari 커넥션 획득 2초·검증 1초입니다.

## DB 스키마 (Flyway)

스키마는 `wallet_service`이며 마이그레이션은 `wallet-service/src/main/resources/db/migration`에 있습니다. Hibernate는 검사만 합니다(`ddl-auto: validate`).

### 테이블

| 테이블 | 역할 | 주요 제약 |
|---|---|---|
| `wallets` | 회원당 지갑 1개 | `uk_wallets_user(user_id)`, 상태 CHECK(`PENDING`·`ACTIVE`·`SUSPENDED`·`CLOSED`, 기본 `PENDING`), `version` |
| `wallet_currencies` | 지갑의 통화별 행, 원장 계정과 1:1. 원장 계정 개설 성공 후에만 만듦 | `uk_wallet_currencies_wallet_currency(wallet_id, currency)`, `uk_wallet_currencies_ledger_account(ledger_account_id)`, 통화 CHECK(`KRW`·`JPY`·`CNY`), 상태 CHECK |
| `charge_requests` | 충전 요청과 SAGA 상태 | `uk_charge_requests_idempotency(user_id, idempotency_key)`, `bank_request_id`·`refund_request_id`·`bank_tx_id`·`refund_bank_tx_id` 각각 UNIQUE, `amount > 0`, 상태 CHECK(8개), `attempt_count >= 0`, `wallet_id` FK |
| `charge_status_history` | 충전 상태 전이 이력 | `charge_id` FK. 최초 접수는 `from_status` NULL |
| `outbox_events` | 범용 Outbox(현재 `CHARGE`만) | 상태 CHECK(`PENDING`·`PUBLISHED`), `PUBLISHED`일 때만 `published_at` |

`charge_requests` 주요 컬럼: `account_number_enc`(계좌번호 암호문), `holder_name_snapshot`(접수 때 실명), `bank_request_id`·`refund_request_id`(접수 때 고정), `bank_tx_id`·`refund_bank_tx_id`·`ledger_ref`(단계 성공 후), `failure_code`, `last_error`(500자), `attempt_count`.

| 인덱스 | 내용 |
|---|---|
| `ix_charge_requests_status_updated` | `(status, updated_at)`. 복구 대상 찾기 |
| `ix_charge_requests_user_created` | `(user_id, created_at DESC)`. 회원 충전 목록 |
| `ix_charge_status_history_charge` | `(charge_id, created_at)` |
| `ix_outbox_events_pending` | `(created_at) WHERE status = 'PENDING'`. 미발행 건 |

- 충전 상태와 단계 결과 값(`bank_tx_id`·`ledger_ref`·`attempt_count` 등)은 엔티티 변경 감지가 아니라 조건부 UPDATE로만 바꾸며 `updated_at`도 쿼리에서 직접 갱신합니다(복구 스케줄러가 `updated_at`으로 찾기 때문).

### 마이그레이션

| 파일 | 내용 |
|---|---|
| `V1__create_wallet_tables.sql` | 다섯 테이블, 제약, 인덱스 |

새 파일은 이미 적용된 파일을 고치지 않고 추가합니다.

## 테스트

```bash
./gradlew :wallet-service:test
```

- DB 없이 실행: `BankAccountTest`, `AesGcmAccountNumberCipherTest`, `BankClientAdapterTest`, `BankFaultInjectionTest`, `BankFeignClientFaultHeaderTest`, `LedgerAccountClientAdapterTest`, `LedgerTransactionClientAdapterTest`, `UserFundingIdentityClientAdapterTest`, `ChargeRecoverySchedulerConfigTest`, `WalletServiceInternalBalancesTest`.
- 통합 테스트는 Testcontainers로 PostgreSQL 컨테이너(`postgres:18.6-bookworm`)를 띄우고 Flyway를 적용해 실행합니다. `OutboxPublisherIntegrationTest`는 Kafka 컨테이너(`apache/kafka:4.2.2`)도 띄웁니다. `.env`나 로컬 compose는 필요 없고 Docker만 실행 중이면 됩니다. Docker가 없으면 건너뜁니다(통과가 아님).

| 통합 테스트 | 범위 |
|---|---|
| `ChargeSagaIntegrationTest` | SAGA 실행기(은행·원장은 대역, 컨슈머는 끄고 리스너 메서드 직접 호출) |
| `ChargeSagaFeignIntegrationTest` | Feign 실패가 실제 어댑터를 거쳐 환불·`MANUAL_REVIEW`·결과 모름으로 이어지는지(대역은 Feign 클라이언트) |
| `ChargeStatusTransitionerIntegrationTest` | 조건부 상태 전이(접수 단계로 만든 `REQUESTED`부터) |
| `ChargeRecoverySchedulerIntegrationTest` | 복구 대상 대기 시간·선점·재실행(은행·원장은 대역, `recover()` 직접 호출) |
| `OutboxPublisherIntegrationTest` | 실제 Kafka로 발행, Outbox 상태, Kafka 중단 시 동작 |
| `OutboxPublishOrderIntegrationTest` | `PENDING` 조회 순서(재시도 적은 순 → 오래된 순) |
| `ChargeQueryIntegrationTest` | 충전 내역 조회 API(회원·관리자) |

## 고려 중

아래 항목은 아직 정해지지 않았습니다. 정해지면 해당 절로 옮깁니다.

| 항목 | 현재 동작 | 정할 것 |
|---|---|---|
| `MANUAL_REVIEW` 처리 | 종료 상태이며 푸는 API·운영자 보정 API 없음 | 은행·원장 실제 결과를 확인해 완료·환불로 정리하는 절차와 API |
| 충전 대사 | 지갑에는 없음. Mock Bank에 거래 기간 목록·`requestId` 단건 조회 API(`/internal/v1/bank/transactions`)가 있고, 원장에 기간 목록 API가 있음 | 은행·원장·충전 기록 대조 방식과 주기 |
| 서비스 간 내부 인증 | 원장·은행 호출에 인증 헤더 없음(코드 TODO) | 내부 인증 헤더 규칙 |
| DB 장애 503 처리 위치 | `WalletDatabaseExceptionHandler`(지갑 전용) | common 처리기로 이전 |
| 충전 통화 | KRW만. DB CHECK는 `KRW`·`JPY`·`CNY` 허용 | 외화 충전 여부 |
| 지갑·통화 상태 변경 | `SUSPENDED`·`CLOSED` 값만 있고 바꾸는 API 없음 | 정지·해지 절차 |
