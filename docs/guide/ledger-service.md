# 원장 서비스 실행·사용 가이드

ledger-service는 모든 도메인(충전·송금·결제·환불·환전)의 자금 이동을 복식부기로 기록합니다. 계정별 잔액을 가지고 있고, 거래 하나의 항목을 한 DB 트랜잭션으로 함께 반영하거나 함께 거절합니다. 다른 서비스가 내부 API로만 호출하며 Gateway로 열지 않습니다.

## 로컬 실행

필요한 것: JDK 21, Docker.

1. `.env.example`을 `.env`로 복사하고 비밀번호 값을 바꿉니다. 실제 값은 저장소에 올리지 않습니다.
2. DB를 실행합니다.

   ```bash
   docker compose up -d postgres
   ```

3. 원장은 다른 서비스를 호출하지 않습니다. 아래만 있으면 됩니다.

   | 서비스 | 포트 | 원장에서 쓰는 곳 |
   |---|---|---|
   | postgres | `.env`의 `POSTGRES_PORT` | `ledger_service` 스키마 |
   | eureka-server | 8761 | 원장 등록(호출하는 서비스가 `ledger-service` 이름으로 찾음) |

4. Spring Boot는 `.env`를 직접 읽지 않으므로 환경 변수로 넘겨 실행합니다.

   ```bash
   set -a; source .env; set +a
   ./gradlew :ledger-service:bootRun
   ```

   - 로컬 포트는 `8085`입니다. `GET http://localhost:8085/actuator/health`로 상태를 확인합니다.
   - 필요한 환경 변수는 `LEDGER_DB_URL`·`LEDGER_DB_USERNAME`·`LEDGER_DB_PASSWORD`입니다. `LEDGER_DB_URL`은 `currentSchema=ledger_service`를 붙입니다(`.env.example` 참고).
   - 배포 설정(`application-prod.yml`)은 `SPRING_CONFIG_NAME=application-prod`로 선택합니다. 배포 포트는 `8080`입니다.
   - compose에는 ledger-service 컨테이너가 없습니다. 로컬에서는 `bootRun`으로 실행합니다.

5. 테이블과 시스템 계정은 앱이 시작할 때 Flyway가 만듭니다([DB 스키마](#db-스키마-flyway)).

Swagger UI는 없습니다(ledger-service에 springdoc 의존성이 없음). API 계약은 이 문서를 기준으로 합니다.

## API 목록

| API | 설명 | 성공 |
|---|---|---|
| `POST /internal/v1/ledger/accounts` | 지갑의 통화별 계정 개설 | 201(새로 만듦)·200(이미 모두 있음) |
| `GET /internal/v1/ledger/accounts?walletId=` | 지갑의 통화별 계정·잔액 조회 | 200 |
| `POST /internal/v1/ledger/transactions` | 거래 등록 | 201(새 기록)·200(같은 내용 재요청) |
| `POST /internal/v1/ledger/transactions/cancel` | 거래 취소(같은 reference를 취소 기록으로 막음) | 201(취소 기록)·200(이미 거래 있음) |
| `GET /internal/v1/ledger/transactions?referenceType=&referenceId=` | reference로 거래 단건 조회 | 200 |
| `GET /internal/v1/ledger/transactions/period?referenceType=&from=&to=` | 기간 거래 목록(커서) | 200 |

- 모두 `/internal/**` 경로입니다. Gateway 외부 라우트에 없으므로 브라우저에서 부를 수 없고, 서비스끼리 Eureka 이름(`ledger-service`)이나 직접 주소로 호출합니다.
- 응답 본문은 `success`·`data`·`error` 공통 형식입니다([common 가이드](common.md#공통-응답오류)). ID는 모두 UUID, 금액은 통화별 최소 단위 정수(KRW 원, JPY 엔, CNY 펀), 시각은 UTC ISO-8601입니다.

### 계정 개설·조회

- 개설 요청 `{walletId}`. KRW·JPY·CNY 세 통화의 `WALLET` 계정을 만듭니다. 이미 있는 통화는 건너뛰므로 재시도·동시 요청에도 멱등입니다.
- 응답 `data`는 계정 목록 `[{accountId, currency, balance, status}]`입니다.
- 조회는 상태와 관계없이 그대로 돌려줍니다. 사용 가능 여부는 호출 쪽이 판단합니다. 계정이 없으면 404 `LEDGER_ACCOUNT_NOT_FOUND`.

## 거래 등록

### 요청

```json
{
  "referenceType": "TRANSFER",
  "referenceId": "6dda1ae2-a470-4f32-84f9-8edaa4404ec5",
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "description": null,
  "originalTransactionId": null,
  "entries": [
    {"ownerType": "USER", "walletId": "…송금인 지갑", "currency": "KRW", "direction": "DEBIT", "amount": 10000, "entryType": "TRANSFER_OUT"},
    {"ownerType": "USER", "walletId": "…수취인 지갑", "currency": "KRW", "direction": "CREDIT", "amount": 10000, "entryType": "TRANSFER_IN"}
  ]
}
```

| 항목 | 규칙 |
|---|---|
| `referenceType` | 필수. `CHARGE`·`PAYMENT`·`PAYMENT_REFUND`·`TRANSFER`·`EXCHANGE` |
| `referenceId` | 필수, UUID. 도메인의 원 거래 ID(충전 ID, 송금 ID 등) |
| `userId` | 선택. 기록용이며 계정 조회에 쓰지 않음 |
| `description` | 선택, 255자 이하 |
| `originalTransactionId` | 선택. 되돌리는 거래가 가리키는 원 원장 거래 ID. 없는 ID면 400 `LEDGER_ORIGINAL_TRANSACTION_NOT_FOUND` |
| `entries` | 필수, 2~20건 |
| `entries[].ownerType` | `USER` 또는 `SYSTEM`. `MERCHANT`는 지정 방식이 정해지지 않아 400 |
| `entries[].walletId` / `accountType` | 아래 [계정 지정](#계정-지정) |
| `entries[].currency` | `KRW`·`JPY`·`CNY` |
| `entries[].direction` | `DEBIT`(차변, 잔액 감소) 또는 `CREDIT`(대변, 잔액 증가) |
| `entries[].amount` | 0보다 큰 정수 |
| `entries[].entryType` | [entry_type 허용 값](#entry_type-허용-값) 11개 중 하나 |

### 계정 지정

| `ownerType` | 넣을 값 | 찾는 계정 |
|---|---|---|
| `USER` | `walletId` + `currency` (`accountType`은 비움) | 그 지갑의 `WALLET` 계정 |
| `SYSTEM` | `accountType` + `currency` (`walletId`는 비움) | [시스템 계정](#시스템-계정) |

둘 다 넣거나 둘 다 비우면 400입니다. 계정이 없으면 거래를 거절(422 `LEDGER_REJECTED_ACCOUNT_NOT_FOUND`)합니다.

### 검증 규칙 (400, 아무것도 기록하지 않음)

- 항목 2건 이상 20건 이하
- 통화별로 `DEBIT` 합 = `CREDIT` 합
- 금액은 0보다 큼. 금액 0 항목은 보내지 않습니다(예: 환전 수수료 0이면 수수료 쌍을 뺌)
- `entryType`은 허용 값 11개 중 하나
- 요청 enum(`referenceType`·`ownerType`·`accountType`·`currency`·`direction`·`entryType`)은 **정확한 이름 문자열만** 받습니다. 숫자(`0`), 숫자 문자열(`"0"`), 소문자(`"charge_in"`)는 400 `COMMON_INVALID_REQUEST`입니다.

### 반영과 거절 판단

1. 같은 reference 거래가 이미 있으면 [멱등성](#멱등성) 규칙으로 응답합니다.
2. 항목마다 계정을 찾습니다. 없으면 `ACCOUNT_NOT_FOUND`로 거절합니다.
3. 계정을 ID 순으로 잠급니다(`FOR NO KEY UPDATE`, 데드락 방지). [계정 잠금 순서](#계정-잠금-순서) 참고.
4. 하나라도 `ACTIVE`가 아니면 `WALLET_SUSPENDED`로 거절합니다.
5. 계정별 순변동(대변 합 − 차변 합)을 더해 `USER` 계정 잔액이 음수가 되면 `INSUFFICIENT_BALANCE`로 거절합니다. `SYSTEM` 계정은 음수를 허용합니다. 항목 순서와 관계없이 순변동으로 판단합니다.
6. 거래·항목·잔액을 한 트랜잭션으로 저장합니다(제한 시간 3초).

### 계정 잠금 순서

`LedgerOppositeTransferIntegrationTest`로 확인한 결과입니다.

- 거래마다 항목 계정을 DB 계정 ID 오름차순(`ORDER BY id`)으로 한 번에 잠급니다.
- 테스트 중 `pg_stat_activity`에서 관측한 실제 SQL의 잠금 절은 `FOR NO KEY UPDATE`였습니다.
- 같은 두 지갑 사이의 반대 방향 송금(A→B, B→A) 두 건이 모두 앞 계정에서 대기하는 동안, 다른 연결이 뒤 계정에 `FOR NO KEY UPDATE NOWAIT`를 걸어 바로 성공했습니다. 대기 중인 요청이 뒤 계정을 먼저 잠그지 않았다는 실행 근거입니다.
- 반대 방향 동시 송금을 50회 반복한 검증에서 송금 형태(`TRANSFER_OUT`/`TRANSFER_IN`) 원장 거래 100건이 모두 `POSTED`되었고, DB 교착 횟수(`pg_stat_database.deadlocks`) 증가량은 0이었습니다.

### entry_type 허용 값

`CHARGE_OUT`, `CHARGE_IN`, `PAYMENT_OUT`, `PAYMENT_IN`, `PAYMENT_REFUND_OUT`, `PAYMENT_REFUND_IN`, `EXCHANGE_OUT`, `EXCHANGE_IN`, `EXCHANGE_FEE`, `TRANSFER_OUT`, `TRANSFER_IN`, `SETTLEMENT_OUT`, `SETTLEMENT_IN`  (13개, `EntryType` enum과 V7 CHECK가 같음).

도메인별 분개(각 서비스의 현재 요청 코드 기준):

| 도메인 | `referenceType` | 차변(DEBIT) | 대변(CREDIT) |
|---|---|---|---|
| 충전 | `CHARGE` | `SYSTEM CHARGE_SOURCE` KRW, `CHARGE_OUT` | `USER` 회원 지갑 KRW, `CHARGE_IN` |
| 송금 | `TRANSFER` | `USER` 송금인 지갑, `TRANSFER_OUT` | `USER` 수취인 지갑, `TRANSFER_IN` |
| 결제 | `PAYMENT` | `USER` 결제자 지갑, `PAYMENT_OUT` | `USER` 가맹점 지갑, `PAYMENT_IN` |
| 환불 | `PAYMENT_REFUND` | `USER` 가맹점 지갑, `PAYMENT_REFUND_OUT` | `USER` 결제자 지갑, `PAYMENT_REFUND_IN` |
| 환전(보내는 금액) | `EXCHANGE` | `USER` 지갑(보내는 통화), `EXCHANGE_OUT` | `SYSTEM FX_POSITION`(보내는 통화), `EXCHANGE_OUT` |
| 환전(수수료) | `EXCHANGE` | `USER` 지갑(보내는 통화), `EXCHANGE_FEE` | `SYSTEM FEE_REVENUE`(보내는 통화), `EXCHANGE_FEE` |
| 환전(받는 금액) | `EXCHANGE` | `SYSTEM FX_POSITION`(받는 통화), `EXCHANGE_IN` | `USER` 지갑(받는 통화), `EXCHANGE_IN` |
| 정산 | `SETTLEMENT` | (결제 담당 확인 중), `SETTLEMENT_OUT` | (결제 담당 확인 중), `SETTLEMENT_IN` |

- 환전은 한 거래에 6항목(수수료 0이면 4항목)입니다. 통화별로 차변·대변이 맞습니다.
- 환불은 `originalTransactionId`에 결제의 원장 거래 ID를 넣습니다.
- 충전은 원화만 있습니다(`CHARGE_SOURCE` 계정이 KRW뿐).
- - 정산은 V7에서 참조 유형과 항목 유형만 추가했습니다. 차변·대변 계정은 결제 담당 확인 후 채웁니다.

### 응답

```json
{
  "success": true,
  "data": {"transactionId": "c92b2030-…", "status": "POSTED"},
  "error": null
}
```

### 멱등성

reference(`referenceType` + `referenceId`)가 원장의 멱등 키입니다. 헤더는 받지 않습니다.

| 상황 | 결과 |
|---|---|
| 처음 요청, 반영 | 201 `POSTED` |
| 처음 요청, 거절 | 422(사유별 코드). `REJECTED` 거래가 기록됨 |
| 같은 reference·같은 내용, 기존 `POSTED` | 200, 같은 `transactionId` |
| 같은 reference·같은 내용, 기존 `REJECTED` | 처음과 같은 422 (다시 판단하지 않음) |
| 같은 reference·다른 내용 | 409 `LEDGER_REFERENCE_CONFLICT` |
| 같은 reference 동시 요청 | 하나만 저장되고 나머지는 위 규칙으로 응답 |

- 같은 내용인지는 요청 해시(SHA-256)로 비교합니다. 포함: `referenceType`, `referenceId`, `originalTransactionId`, 항목(`ownerType`, `walletId` 또는 `accountType`, `currency`, `direction`, `amount`, `entryType`). 제외: `description`, `userId`.
- 항목 순서만 다른 요청은 같은 내용으로 봅니다.
- 거절된 reference는 다시 쓸 수 없습니다. 잔액을 채운 뒤 다시 시도하려면 새 reference가 필요합니다.
- 중복 방지는 DB `UNIQUE(reference_type, reference_id)`로 보장합니다.

같은 reference 동시 요청은 `LedgerSameReferenceIntegrationTest`로 확인했습니다. 계정 잠금을 외부 연결로 쥔 채 6건을 동시에 보내, 6건 모두 기존 거래가 없다고 읽고 잠금 대기에 들어간 것을 확인한 뒤 풀었습니다.

| 경우 | 결과 |
|---|---|
| 같은 내용, 반영(`POSTED`) | 201 1건, 200 5건. 거래 행 1건과 원장 항목 2건만 생성되고 잔액은 한 번만 변경 |
| 같은 내용, 거절(`REJECTED`) | 422 6건. `REJECTED` 거래 행 1건만 생성되고 원장 항목·잔액 변경 없음 |
| 다른 내용 | 먼저 등록된 내용이 반영되고(201·200), 다른 내용 요청은 409 `LEDGER_REFERENCE_CONFLICT` |

- 경합에서 진 요청은 거래 INSERT의 UNIQUE 제약 충돌 뒤 새 트랜잭션에서 기존 거래를 다시 조회해 위 규칙으로 응답합니다.

### 응답 코드와 호출 쪽 처리 권장

| 응답 | 의미 | 호출 쪽 처리 |
|---|---|---|
| 201 | 새로 반영 | 성공 처리 |
| 200 | 이미 같은 내용으로 반영됨 | 201과 같이 성공 처리 |
| 422 `LEDGER_REJECTED_*` | 확정 거절. 자금 이동 없음 | 실패로 확정. 같은 reference 재전송은 같은 422 |
| 409 `LEDGER_REFERENCE_CONFLICT` | 같은 reference에 다른 내용 기록이 있음 | 자동 재시도 중단, ERROR 로그·운영자 확인. 기존 기록은 단건 조회로 확인 |
| 400 | 요청을 잘못 만든 버그·계약 불일치 | 재시도해도 같음. 자동 재시도 중단, ERROR 로그 |
| 503 `COMMON_SERVICE_UNAVAILABLE` | DB 연결 불가·커넥션 획득 실패·쿼리/트랜잭션 시간 초과. 롤백됨 | 결과 모름으로 보고 같은 reference·같은 내용으로 재전송 |
| 500 | 예상하지 못한 오류 | 결과 모름으로 보고 재전송 또는 단건 조회로 확인 |
| 읽기 타임아웃·연결 실패 | 원장이 처리했을 수 있음 | 실패로 단정하지 않음. 같은 내용 재전송(한 번만 반영됨) 또는 단건 조회 |

재전송 요청은 처음과 같은 값(저장된 값)으로 만듭니다. 내용이 바뀌면 409가 됩니다.

### DB 장애 응답 (테스트로 확인)

`LedgerDatabaseFailureIntegrationTest`로 관측한 결과입니다. 시간은 요청 시작(또는 트랜잭션 시작)부터 응답까지입니다.

| 상황 | 관측 결과 | HTTP 응답 |
|---|---|---|
| 커넥션 획득 실패 | 약 2.0초, `CannotCreateTransactionException` → `JDBCConnectionException` → `SQLTransientConnectionException` | 503 |
| 잠금 대기 중 트랜잭션 제한 초과 | 약 3.0초, `QueryTimeoutException` → SQLState `57014` | 503 |
| 교착 | 약 1.0초, `CannotAcquireLockException` → SQLState `40P01` | 503 |

- 트랜잭션 제한 초과 때 PostgreSQL 메시지는 `canceling statement due to user request`였습니다. `57014`는 실제 SQL이 실행 중에 취소된 결과입니다.
- 교착 테스트에서 `deadlock_timeout`은 1초였고, 외부 연결과 교착이 나 앱 요청이 희생됐습니다.
- 트랜잭션 제한 초과와 교착 테스트에서는 거래 행 0건, 잔액 불변이었습니다. 잠금 해제 뒤 같은 요청을 재시도하면 201이었습니다.
- 풀 고갈 테스트(같은 계정을 잠근 채 12건 동시): 커넥션을 얻은 10건은 약 3.0초에 트랜잭션 제한 초과, 못 얻은 2건은 약 2.0초에 커넥션 획득 실패로 모두 503이었습니다. 단언은 reference당 거래 행 1건 이하와 잔액 = 시작 잔액 + `POSTED` 순변동이며, 이번 실행은 `POSTED` 0건이라 잔액 불변을 확인한 것입니다. 이 테스트에서는 재시도하지 않았습니다.

**미확인** (확인된 사실이 아님)

- 트랜잭션 제한 시간을 실제로 적용한 계층: Hibernate의 초 단위 제한인지 Spring의 밀리초 단위 힌트인지 확정하지 않았습니다. 약 3.0초에 취소되어 밀리초 단위 적용 가능성과 부합하지만 추정입니다.
- 다음 SQL을 보내기 전에 제한 시간이 이미 지난 경우의 예외 변환과 HTTP 응답: 미확인입니다. 500 가능성을 배제하지 않습니다.
  - 원장을 부르는 다섯 경로(결제·환불·정산·송금·충전)는 모두 4xx가 아닌 응답을 결과 모름으로 받아 같은 reference로 재전송합니다(`FeignErrors.isRejected` 기준). 그래서 원장이 500을 돌려줘도 돈이 어긋나지 않고, 500과 503의 차이는 응답 의미와 모니터링에만 영향을 줍니다.
- 커밋 플러시 또는 COMMIT 중 실패했을 때의 예외 변환과 HTTP 응답: 미확인입니다. 결과를 모르는 경우(커밋됐는지 알 수 없음)도 남아 있습니다.

## 거래 취소

`POST /internal/v1/ledger/transactions/cancel`

결과를 모르는 등록 요청을 호출 쪽이 실패로 확정하기 전에, 같은 reference를 취소 기록으로 먼저 차지합니다. 호출 쪽이 기다리기를 그만둔 등록 요청은 원장 서버에서 스레드를 기다리다 늦게 커밋될 수 있는데, 이 대기 시간은 설정으로 묶여 있지 않습니다. 단건 조회의 404는 읽기라 이후 커밋을 막지 못하므로, 취소 기록을 먼저 남기고 그 응답으로 확정합니다.

### 요청

```json
{
  "referenceType": "PAYMENT",
  "referenceId": "6dda1ae2-…",
  "userId": "8f0c…",
  "description": "결제 시간 초과 취소"
}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `referenceType`·`referenceId` | 필수 | 취소할 등록 요청과 같은 값 |
| `userId` | 선택 | 요청자. 등록 요청과 같은 의미(기록용) |
| `description` | 선택 | 255자 이하 |

금액·항목은 받지 않습니다.

### 응답

응답 `data`는 [단건 조회](#단건-조회)와 같은 형식입니다.

| 그 reference의 상태 | 처리 | 응답 |
|---|---|---|
| 거래 없음 | `REJECTED` + `rejectReason: CANCELLED_BY_REQUESTER` 저장. 항목·계정 잠금·잔액 변화 없음 | 201, 빈 `entries` |
| 이미 `POSTED` | 바꾸지 않음 | 200, 기존 거래(항목 포함) |
| 이미 `REJECTED`(원장 거절 또는 앞선 취소) | 바꾸지 않음 | 200, 기존 거래와 원래 사유 |
| 형식 오류(`referenceType`·`referenceId` 없음 등) | 기록 없음 | 400 `COMMON_INVALID_REQUEST` |
| DB 연결 불가·커넥션 획득 실패·트랜잭션 시간 초과 | 롤백 | 503 `COMMON_SERVICE_UNAVAILABLE` (같은 요청으로 재전송) |

등록 API와 다른 점:

- `REJECTED`(취소 기록)도 성공 결과라 201·200 본문으로 돌려줍니다. 422로 바꾸지 않습니다.
- 같은 reference에 다른 내용의 거래가 있어도 409가 아니라 그 거래를 200으로 돌려줍니다. 호출 쪽은 응답의 `status`·`entries`로 등록이 먼저 반영됐는지 판단합니다.

### 등록과의 관계

- 취소 뒤 같은 reference로 등록이 오면 기존 규칙대로 409 `LEDGER_REFERENCE_CONFLICT`이고, 잔액과 취소 기록은 그대로입니다. 취소 기록의 요청 해시는 `cancel-v1` + referenceType + referenceId로 따로 계산해 어떤 등록 해시와도 같지 않습니다.
- 등록과 동시에 들어오면 reference UNIQUE로 먼저 커밋된 쪽만 남습니다. 취소는 계정을 잠그지 않아, 등록이 계정 잠금을 기다리는 동안 먼저 커밋됩니다. 취소가 지면(등록이 먼저 저장) 새 트랜잭션에서 그 거래를 다시 읽어 200으로 돌려줍니다.
- 같은 취소를 반복해도 거래는 하나이고, 같은 `transactionId`를 200으로 돌려줍니다.
- `reject_reason`은 값 제약이 없는 `VARCHAR(50)`이고 제약은 "`REJECTED`일 때만 사유 있음"뿐이라 마이그레이션은 없습니다.

### 테스트로 확인

`LedgerCancelIntegrationTest`(실제 Postgres·실제 HTTP):

| 상황 | 결과 |
|---|---|
| 거래 없음 | 201, `REJECTED`·`CANCELLED_BY_REQUESTER`, 거래 행 1건·항목 0건 |
| 같은 취소 3회(설명만 다른 요청 포함) | 201·200·200, 같은 `transactionId`, 거래 행 1건 |
| 취소 뒤 같은 reference로 충전 등록 | 409 `LEDGER_REFERENCE_CONFLICT`, 잔액 0, 취소 기록 유지 |
| 이미 `POSTED`(충전) | 200, `POSTED`·항목 2개, 잔액 그대로 |
| 이미 `REJECTED`(잔액 부족 송금) | 200, `INSUFFICIENT_BALANCE` 그대로 |
| 등록이 계정 잠금을 기다리는 중 취소(`LedgerLockProbe`) | 취소 201, 등록 409, 거래는 취소 1건·항목 0건·잔액 0. 등록 대기 확인 뒤 취소하고 65ms 만에 잠금 해제 |
| 등록이 거래 행을 INSERT했지만 커밋 전일 때 취소 → 등록 커밋 | 취소가 reference UNIQUE에서 기다린 뒤 200, 등록 거래(`POSTED`, 같은 ID) 반환, 거래 행 1건·취소 기록 없음 |
| 같은 상황에서 등록 롤백 | 취소가 기다린 뒤 201, `CANCELLED_BY_REQUESTER`, 거래 행 1건 |
| 같은 reference로 취소 6건 동시 | 201 1건·200 5건, 모두 같은 `transactionId`, 거래 행 1건 |
| `referenceId` 없음 | 400, 거래 행 변화 없음 |

커밋 전 행은 앱 풀 밖 연결이 같은 reference 행을 INSERT만 하고 쥔 채로 재현했습니다. 취소가 잠금을 기다리는 것을 `pg_stat_activity`(`wait_event_type = 'Lock'`)로 확인한 뒤 커밋·롤백했고, 이 대기도 취소의 트랜잭션 제한 3초 안입니다.

## 조회

### 단건 조회

`GET /internal/v1/ledger/transactions?referenceType=TRANSFER&referenceId={ID}`

```json
{
  "success": true,
  "data": {
    "transactionId": "c92b2030-…",
    "status": "POSTED",
    "rejectReason": null,
    "referenceType": "TRANSFER",
    "referenceId": "6dda1ae2-…",
    "createdAt": "2026-11-02T01:02:03.123456Z",
    "entries": [
      {"ownerType": "USER", "walletId": "…", "accountType": "WALLET", "currency": "KRW", "direction": "DEBIT", "amount": 10000, "entryType": "TRANSFER_OUT"}
    ]
  },
  "error": null
}
```

- `rejectReason`은 `REJECTED`일 때만 값이 있습니다(`INSUFFICIENT_BALANCE`·`WALLET_SUSPENDED`·`ACCOUNT_NOT_FOUND`, [거래 취소](#거래-취소)로 남긴 기록은 `CANCELLED_BY_REQUESTER`).
- `entries`는 저장된 계정 값을 그대로 보여 줍니다. `USER` 항목은 `walletId` + `accountType: "WALLET"`, `SYSTEM` 항목은 `walletId: null` + `accountType`입니다. 요청 때 `USER`에 `accountType`을 비웠어도 응답에는 `WALLET`이 나오므로, 요청과 비교할 때 이 차이를 고려합니다.
- 항목 순서는 항목 ID 순으로 고정이며 요청 순서와 다를 수 있습니다. 비교는 순서 없이 합니다.
- `balanceAfter`는 내보내지 않습니다.
- `REJECTED` 거래는 항목이 없어 `entries`가 빈 배열입니다.
- 없으면 404 `LEDGER_TRANSACTION_NOT_FOUND`.
- 쿼리는 항목 수와 관계없이 2회(거래 1, 항목·계정 JOIN 1)입니다.

### 기간 목록

`GET /internal/v1/ledger/transactions/period?referenceType=CHARGE&from=2026-11-01T00:00:00Z&to=2026-11-02T00:00:00Z&size=100`

| 파라미터 | 규칙 |
|---|---|
| `referenceType` | 필수. 한 번에 한 유형 |
| `from` | 필수, 포함 |
| `to` | 필수, 미포함. `from`과 같으면 빈 목록, `from`보다 앞이면 400 `LEDGER_INVALID_PERIOD` |
| `cursor` | 선택. 이전 응답의 `nextCursor` |
| `size` | 선택. 기본 100, 100을 넘으면 100, 1 미만은 400 `LEDGER_INVALID_PAGE_SIZE` |

- 시각 형식: 초까지 적은 ISO-8601 시각. `Z` 또는 오프셋(`+09:00`)을 붙입니다. 날짜만(`2001-01-01`) 보내면 400입니다. URL 쿼리에서 `+`는 공백으로 읽히므로 `%2B`로 인코딩합니다(예: `2026-11-01T09:00:00%2B09:00`).
- 허용 범위: `from`은 `0001-01-01T00:00:00Z` ~ `9999-12-31T23:59:59.999999Z`, `to`는 `0001-01-01T00:00:00Z` ~ `+10000-01-01T00:00:00Z`(URL에서는 `%2B10000-01-01T00:00:00Z`). 범위 밖은 400 `LEDGER_INVALID_PERIOD`.
- DB 시각 정밀도는 마이크로초입니다. `from`·`to`의 마이크로초 미만 값은 다음 마이크로초로 올립니다. `from > to` 검사는 올리기 전 값으로 합니다.
- 정렬: `createdAt` 오름차순, 같은 시각은 `transactionId` 오름차순. `REJECTED` 거래도 포함하며 `entries`는 빈 배열입니다.
- 응답 `data`: `items`(단건 조회와 같은 형식의 목록), `nextCursor`(다음 페이지가 없으면 `null`). 남은 행이 정확히 `size`건이면 `nextCursor`는 `null`입니다.
- 커서: 조회 조건(`referenceType`·`from`·`to`)과 마지막으로 돌려준 행 위치를 담은 불투명 문자열입니다. 다음 페이지는 **같은 조건**에 `cursor`를 붙여 보냅니다. 조건이 다르면 400 `LEDGER_CURSOR_MISMATCH`, 풀 수 없거나 위치가 기간 밖인 커서는 DB 조회 전에 400 `LEDGER_INVALID_CURSOR`입니다. 조건은 시각 값으로 비교하므로 `Z`와 `+09:00`처럼 표기만 다른 같은 시각은 같은 조건입니다.
- 같은 시각 거래가 페이지 경계에 걸려도 중복·누락 없이 이어집니다.
- 쿼리 수: 거래가 있는 페이지 2회(거래 1, 항목 JOIN 1), 빈 페이지 1회.
- 인덱스 `ix_ledger_transactions_reference_created(reference_type, created_at, id)`로 정렬 없이 읽습니다.

### 커밋 지연 한계와 대사

`createdAt`은 커밋 전에 앱이 정합니다. 그래서 커서가 어떤 시각을 지나간 뒤에 그보다 앞선 `createdAt`의 거래가 커밋되면, 이어지는 페이지에서 그 거래가 빠집니다. 처음부터 다시 조회하면 보입니다(테스트로 재현됨).

대사 쪽 권장:

- 현재 시각에 가까운 열린 구간이 아니라 이미 끝난 닫힌 기간(`to`가 충분히 지난 시각)을 조회합니다. 거래 기록 트랜잭션 제한 시간은 3초입니다.
- 이전 조회 구간과 겹치게 다시 조회하고 `transactionId`로 중복을 제거합니다.

## 시스템 계정

마이그레이션이 시드합니다(`owner_type = SYSTEM`, `wallet_id = NULL`, 잔액 0, `ACTIVE`). 음수 잔액을 허용합니다.

| `accountType` | 통화 | 시드 | 쓰는 곳 |
|---|---|---|---|
| `CHARGE_SOURCE` | KRW | V3 | 충전의 차변(충전금 출처) |
| `FX_POSITION` | KRW·JPY·CNY | V4 | 환전 회사 통화 보유분 |
| `FEE_REVENUE` | KRW·JPY·CNY | V4 | 환전 수수료 수익 |

`MERCHANT_SETTLEMENT`는 허용 값에만 있고 시드된 계정은 없습니다.

가맹점 계정은 payment-service가 지갑 계정 개설 API(`POST /internal/v1/ledger/accounts`)에 **가맹점 ID를 `walletId`로** 넣어 만듭니다([결제 가이드](payment.md)). 그래서 원장에서는 가맹점 계정도 `ownerType: USER`, `accountType: WALLET`으로 보이고, 결제·환불 항목도 `USER` + 가맹점 ID로 지정합니다. `ownerType: MERCHANT`는 쓰지 않습니다.

## 오류 응답

| 상태 | 코드 | 상황 |
|---|---|---|
| 400 | `COMMON_INVALID_REQUEST` | 본문 검증 실패, 항목 수·차대 불일치·금액·계정 지정·entryType 오류, enum 형식 오류, 필수 파라미터 누락 |
| 400 | `LEDGER_ORIGINAL_TRANSACTION_NOT_FOUND` | `originalTransactionId`의 거래가 없음 |
| 400 | `LEDGER_INVALID_PERIOD` | `from`이 `to`보다 늦음, 허용 범위 밖 시각 |
| 400 | `LEDGER_INVALID_PAGE_SIZE` | `size` 1 미만 |
| 400 | `LEDGER_INVALID_CURSOR` | 풀 수 없거나 위치가 기간 밖인 커서 |
| 400 | `LEDGER_CURSOR_MISMATCH` | 커서의 조건과 요청 조건이 다름 |
| 404 | `LEDGER_ACCOUNT_NOT_FOUND` | 지갑 계정 조회에서 계정 없음 |
| 404 | `LEDGER_TRANSACTION_NOT_FOUND` | reference의 거래 없음 |
| 409 | `LEDGER_REFERENCE_CONFLICT` | 같은 reference로 다른 내용의 거래가 이미 있음 |
| 422 | `LEDGER_REJECTED_INSUFFICIENT_BALANCE` | `USER` 계정 잔액 부족 거절 |
| 422 | `LEDGER_REJECTED_WALLET_SUSPENDED` | 항목 계정 중 `ACTIVE`가 아닌 계정이 있어 거절 |
| 422 | `LEDGER_REJECTED_ACCOUNT_NOT_FOUND` | 항목 계정이 없어 거절 |
| 422 | `LEDGER_REJECTED_CANCELLED_BY_REQUESTER` | 취소 기록이 있는 reference에 같은 요청 해시로 등록. 취소 해시는 등록 해시와 달라 실제로는 409가 됨 |
| 500 | `COMMON_INTERNAL_SERVER_ERROR` | 예상하지 못한 오류 |
| 503 | `COMMON_SERVICE_UNAVAILABLE` | DB 연결 불가·커넥션 획득 실패·쿼리/트랜잭션 시간 초과 |

- 404는 경로 없음(`COMMON_NOT_FOUND`)과 구분하려고 원장 전용 코드를 씁니다.
- 503 처리는 `LedgerDatabaseExceptionHandler`가 임시로 맡고 있습니다(추후 common 처리기로 이전 예정).

## DB 스키마 (Flyway)

스키마는 `ledger_service`이며 마이그레이션은 `ledger-service/src/main/resources/db/migration`에 있습니다. Hibernate는 검사만 합니다(`ddl-auto: validate`).

### 테이블

| 테이블 | 역할 | 주요 제약 |
|---|---|---|
| `ledger_accounts` | 계정과 현재 잔액 | `ck_ledger_accounts_user_balance`(USER 잔액 ≥ 0), USER는 `wallet_id` 필수·SYSTEM은 NULL, 통화·유형·상태 CHECK, `uk_ledger_accounts_id_currency(id, currency)`(항목 FK 대상) |
| `ledger_transactions` | 거래(사건) | `uk_ledger_transactions_reference(reference_type, reference_id)` 멱등 키, `REJECTED`일 때만 `reject_reason`, `original_transaction_id` 자기 참조 FK, `request_hash` 64자 |
| `ledger_entries` | 거래 항목(계정별 증감) | `amount > 0`, `direction` CHECK, `fk_ledger_entries_account(account_id, currency)`로 항목 통화 = 계정 통화 강제, `ck_ledger_entries_entry_type`(V5) |

| 인덱스 | 내용 |
|---|---|
| `uk_ledger_accounts_wallet` | `(wallet_id, account_type, currency)` 부분 UNIQUE. 계정 개설 멱등 근거 |
| `uk_ledger_accounts_system` | `(account_type, currency) WHERE owner_type = 'SYSTEM'`. 시스템 계정 중복 방지 |
| `ix_ledger_entries_transaction` | 거래별 항목 조회 |
| `ix_ledger_entries_account_created` | `(account_id, created_at)` |
| `ix_ledger_transactions_reference_created` | `(reference_type, created_at, id)`. 기간 목록(V6) |

### 마이그레이션

| 파일 | 내용 |
|---|---|
| `V1__create_ledger_tables.sql` | 세 테이블, 제약, 인덱스 |
| `V2__rename_payment_cancel_to_refund.sql` | `referenceType` `PAYMENT_CANCEL` → `PAYMENT_REFUND` |
| `V3__seed_charge_source_account.sql` | `CHARGE_SOURCE` KRW 시스템 계정 |
| `V4__seed_exchange_system_accounts.sql` | `FX_POSITION`·`FEE_REVENUE` 세 통화 시스템 계정 |
| `V5__add_entry_type_check.sql` | entry_type 11개 CHECK(`NOT VALID`) |
| `V6__add_ledger_transactions_reference_created_index.sql` (+ `.sql.conf`) | 기간 목록 인덱스(`CONCURRENTLY`) |
| `V7__add_settlement_types.sql` | `referenceType`에 `SETTLEMENT`, entry_type에 `SETTLEMENT_OUT`·`SETTLEMENT_IN` 추가(entry_type은 `NOT VALID` 유지) |

### 기록은 INSERT만

- `ledger_transactions`·`ledger_entries`는 INSERT만 합니다. 엔티티가 `@Immutable`이고 모든 컬럼이 `updatable = false`입니다. 다만 이것은 JPA 경로만 막고, 네이티브 SQL·DB 직접 수정은 막지 못합니다.
- 잘못 기록한 거래는 고치지 않고 반대 방향의 새 거래를 만들고 `originalTransactionId`로 원거래를 가리킵니다.
- `ledger_accounts`는 잔액(`balance`)·`updated_at`을 거래 반영 때 갱신합니다.

### V5 `NOT VALID`

- 팀원 로컬 DB에 이름 변경 전 entry_type 값이 남아 있을 수 있어 `NOT VALID`로 추가했습니다. 새로 들어오는 행만 검사하고 기존 행은 검사하지 않습니다. 같은 이유로 앱은 `entryType`을 enum이 아니라 문자열로 저장·조회합니다(옛 값이 남은 행도 읽을 수 있게).
- V7에서 정산 값을 추가할 때도 같은 이유로 `NOT VALID`를 유지했습니다. `referenceType` CHECK는 기존 제약이 이미 검증된 상태이고 새 목록이 기존 값을 모두 포함하므로 검증된 제약으로 다시 만들었습니다.

### V6 `CONCURRENTLY`

- 운영 중 쓰기를 막지 않도록 `CREATE INDEX CONCURRENTLY`로 만듭니다.
- 트랜잭션 안에서 실행할 수 없어 같은 이름의 `.sql.conf`에 `executeInTransaction=false`를 두고 이 파일만 트랜잭션 없이 실행합니다.
- Flyway 잠금은 세션 advisory lock을 씁니다(`spring.flyway.postgresql.transactional-lock: false`, `application.yml`·`application-prod.yml` 모두). 트랜잭션 잠금은 잠금 트랜잭션이 열린 동안 `CONCURRENTLY`를 멈추게 하기 때문입니다.
- `IF NOT EXISTS`는 쓰지 않습니다. 실패로 남은 INVALID 인덱스를 건너뛰고 성공 처리되는 것을 막기 위해서입니다.

### 번호 규칙

- 파일 이름은 `V{번호}__{설명}.sql`이며 번호는 정수입니다. 새 파일은 **머지 직전에 dev의 최신 번호 + 1**로 정합니다. 다른 브랜치와 번호가 겹치지 않게 하기 위해서입니다.
- 이미 적용된 파일은 고치지 않고 새 파일을 추가합니다.

## 테스트

```bash
./gradlew :ledger-service:test
```

- `LedgerRequestHasherTest`는 DB 없이 실행됩니다.
- DB 통합 테스트(`LedgerEntryTypeIntegrationTest`, `LedgerTransactionQueryIntegrationTest`, `LedgerTransactionPeriodIntegrationTest`, `LedgerSameReferenceIntegrationTest`, `LedgerOppositeTransferIntegrationTest`, `LedgerDatabaseFailureIntegrationTest`)는 Testcontainers로 PostgreSQL 컨테이너(`postgres:18.6-bookworm`)를 띄우고 Flyway를 적용해 실행합니다. `.env`나 로컬 compose DB는 필요 없고 Docker만 실행 중이면 됩니다. Docker가 없으면 건너뜁니다(통과가 아님).

### 성능 측정

```bash
./gradlew :ledger-service:performanceTest
```

- `@Tag("performance")` 테스트(`LedgerTransactionPeriodPerformanceTest`)는 기본 `test`에서 빠지고 이 태스크로만 실행됩니다.
- 기간 목록의 V6 인덱스 전후를 측정합니다. Flyway를 V5까지만 적용한 뒤 규모(1만·10만·120만 거래)마다 데이터를 만들고 인덱스 없이 측정 → V6 실행 → 다시 측정합니다. 결과는 표준 출력의 `===== PERF REPORT =====` 구간에 나옵니다.
- 대량 데이터를 만들어 기본 테스트보다 오래 걸립니다. 매번 다시 실행됩니다(`upToDateWhen false`).

## 고려 중

아래 항목은 아직 정해지지 않았습니다. 정해지면 해당 절로 옮깁니다.

| 항목 | 현재 동작 | 정할 것 |
|---|---|---|
| V5 CHECK 검증 | `NOT VALID`라 기존 행은 검사하지 않음 | 모든 환경의 옛 값 정리 후 `ALTER TABLE … VALIDATE CONSTRAINT ck_ledger_entries_entry_type` 마이그레이션 추가 시점 |
| prod 설정 | `application-prod.yml`에 `spring.flyway.default-schema`·`schemas`와 Hikari `connection-timeout`·`validation-timeout`이 없음(`application.yml`에만 있음) | 배포 설정에 같은 값을 둘지 |
| 취소·되돌림 분개 | `originalTransactionId`와 원거래 존재 검사만 있음. 되돌림용 entry_type 없음 | 완료된 송금 등의 취소 분개(referenceType·entryType, 원거래 연결) |
| referenceType별 추가 검증 | 없음(코드 TODO) | 환불의 원거래 상태·중복·금액 등 유형별 정책. 해당 도메인 담당이 추가 |
| `MERCHANT` 계정 지정 | 요청에 쓰면 400. 가맹점은 `USER` + 가맹점 ID 지갑 계정 사용 | 가맹점 계정 지정 방식 |
| `CHARGE_SOURCE` 잠금 | 모든 충전이 같은 계정을 잠금(핫 계정) | 성능 측정 후 개선 |
| DB 장애 503 처리 위치 | `LedgerDatabaseExceptionHandler`(원장 전용) | common 처리기로 이전 |
| 계정 상태 변경 | `SUSPENDED`·`CLOSED` 값은 있으나 바꾸는 API 없음 | 정지·해지 절차 |
| 같은 reference 충돌 로그 | 동시 요청의 정상 UNIQUE 충돌 처리 중 Hibernate `SqlExceptionHelper`(로거 `org.hibernate.orm.jdbc.error`)가 충돌 1건당 WARN 두 줄(`SQLState: 23505`, `duplicate key value violates unique constraint "uk_ledger_transactions_reference"`)을 남김. 동시성 테스트 3개 시나리오에서 관측. 정상 처리 과정의 로그라 버그로 단정하지 않음 | 로그 노이즈를 줄일지(운영 개선 후보) |
