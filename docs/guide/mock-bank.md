# Mock 은행 실행·사용 가이드

mock-bank는 충전에서 외부 은행 역할을 하는 대역 서비스입니다. 회원 계좌에서 출금해 플랫폼 계좌로 옮기고(충전 출금), 그 출금을 되돌립니다(환불). 거래 기록을 조회하는 API도 있습니다. wallet-service가 내부 API로만 호출하며 Gateway로 열지 않습니다. 충전 흐름 전체는 [지갑 가이드](wallet-service.md#충전-흐름)에서 다룹니다.

## 로컬 실행

필요한 것: JDK 21, Docker.

1. `.env.example`을 `.env`로 복사하고 비밀번호 값을 바꿉니다. 실제 값은 저장소에 올리지 않습니다.
2. DB를 실행합니다.

   ```bash
   docker compose up -d postgres
   ```

3. Mock 은행은 다른 서비스를 호출하지 않고 Eureka에도 등록하지 않습니다. 아래만 있으면 됩니다.

   | 서비스 | 포트 | Mock 은행에서 쓰는 곳 |
   |---|---|---|
   | postgres | `.env`의 `POSTGRES_PORT` | `mock_bank` 스키마 |

4. Spring Boot는 `.env`를 직접 읽지 않으므로 환경 변수로 넘겨 실행합니다.

   ```bash
   set -a; source .env; set +a
   ./gradlew :mock-bank:bootRun
   ```

   - 포트는 `8090`입니다(로컬·배포 설정 같음). `GET http://localhost:8090/actuator/health`로 상태를 확인합니다. actuator는 `health`·`prometheus`만 엽니다.
   - 필요한 환경 변수는 `MOCK_BANK_DB_URL`·`MOCK_BANK_DB_USERNAME`·`MOCK_BANK_DB_PASSWORD`입니다. `MOCK_BANK_DB_URL`은 `currentSchema=mock_bank`를 붙입니다(`.env.example` 참고).
   - 배포 설정(`application-prod.yml`)은 `SPRING_CONFIG_NAME=application-prod`로 선택합니다. [장애 주입](#장애-재현)은 기본 꺼짐이고, 켜더라도 prod 프로필(`SPRING_PROFILES_ACTIVE=prod`)에서는 비활성이므로 배포에서는 이 값도 함께 지정합니다.
   - compose에는 mock-bank 컨테이너가 없습니다. 로컬에서는 `bootRun`으로 실행합니다.

5. 테이블과 시드 계좌는 앱이 시작할 때 Flyway가 만듭니다([DB 스키마](#db-스키마-flyway)).

Eureka 클라이언트 의존성이 없어 Eureka에 등록하지 않습니다. 그래서 지갑을 로컬에서 실행할 때 `MOCK_BANK_URL=http://localhost:8090`을 넘겨야 합니다([지갑 가이드](wallet-service.md#로컬-실행)).

Swagger UI는 없습니다(mock-bank에 springdoc 의존성이 없음). API 계약은 이 문서를 기준으로 합니다.

## API 목록

| API | 설명 | 성공 |
|---|---|---|
| `POST /internal/v1/bank/withdrawals` | 출금(회원 계좌 → 플랫폼 계좌) | 201(새로 성공)·200(업무 거절 또는 같은 `requestId` 재요청) |
| `POST /internal/v1/bank/refunds` | 환불(플랫폼 계좌 → 원출금 계좌) | 201(새로 성공)·200(업무 거절 또는 같은 `requestId` 재요청) |
| `GET /internal/v1/bank/transactions/{requestId}` | `requestId`로 거래 단건 조회 | 200 |
| `GET /internal/v1/bank/transactions?from=&to=&page=&size=` | 기간 거래 목록(페이지) | 200 |
| `GET /internal/v1/bank/faults/withdrawal-holds` | 은행별 출금 붙잡기 상태(측정용, [장애 재현](#은행별-출금-붙잡기)이 켜졌을 때만) | 200 |
| `PUT /internal/v1/bank/faults/withdrawal-holds/{bankCode}` | 그 은행 출금 붙잡기 켜기·대기 시간 바꾸기 | 200 |
| `DELETE /internal/v1/bank/faults/withdrawal-holds/{bankCode}` | 그 은행 출금 붙잡기 끄기 | 200 |

- 모두 `/internal/**` 경로입니다. Gateway 외부 라우트에 없으므로 브라우저에서 부를 수 없고, 직접 주소(`http://localhost:8090`)로 호출합니다.
- 응답 본문은 `success`·`data`·`error` 공통 형식입니다([common 가이드](common.md)). 금액은 원 단위 정수, 시각은 UTC ISO-8601입니다.
- 업무 거절(잔액 부족 등)은 오류가 아니라 **200 + `status: FAILED`** 입니다. HTTP 오류 코드는 요청 자체가 잘못됐을 때만 씁니다([오류 응답](#오류-응답)).

## 출금·환불

### 요청

출금:

```json
{
  "requestId": "6dda1ae2-a470-4f32-84f9-8edaa4404ec5",
  "bankCode": "004",
  "accountNumber": "12340101234567",
  "holderName": "유재석",
  "amount": 10000
}
```

| 항목 | 규칙 |
|---|---|
| `requestId` | 필수, 공백 불가, 100자 이하. 호출 쪽이 정하는 멱등 키 |
| `bankCode` | 필수, 숫자 3자리 |
| `accountNumber` | 필수, 숫자 10~20자리 |
| `holderName` | 필수, 공백 불가, 100자 이하. 앞뒤 공백을 정리한 뒤 비교 |
| `amount` | 필수, 0보다 큰 정수 |

환불:

```json
{
  "requestId": "…",
  "originalTxId": "…원출금의 bankTxId",
  "amount": 10000
}
```

| 항목 | 규칙 |
|---|---|
| `requestId` | 필수, 공백 불가, 100자 이하 |
| `originalTxId` | 필수, UUID. 되돌릴 출금의 `bankTxId` |
| `amount` | 필수, 0보다 큰 정수. 원출금 금액과 같아야 함(전액만) |

형식이 틀리면 400 `COMMON_INVALID_REQUEST`이고 아무것도 기록하지 않습니다.

### 판정 순서

출금(한 DB 트랜잭션):

1. 같은 `requestId` 기록이 있으면 같은 내용일 때 그대로 돌려주고, 유형이나 내용이 다르면 409입니다([멱등성](#멱등성)).
2. `bankCode` + `accountNumber`로 **회원(`USER`) 계좌**를 찾습니다. 없으면 `ACCOUNT_NOT_FOUND`. 플랫폼 계좌 번호를 넣어도 `ACCOUNT_NOT_FOUND`입니다.
3. 계좌가 `ACTIVE`가 아니면 `ACCOUNT_SUSPENDED`.
4. 예금주가 정확히 같지 않으면 `HOLDER_MISMATCH`.
5. 잔액 차감(`WHERE balance >= amount` 조건부 UPDATE)이 0건이면 `INSUFFICIENT_BALANCE`.
6. 플랫폼 계좌 잔액을 늘리고 `SUCCEEDED` 기록을 저장합니다.

환불(한 DB 트랜잭션):

1. 같은 `requestId` 기록이 있으면 같은 내용일 때 그대로 돌려주고, 유형이나 내용이 다르면 409입니다.
2. `originalTxId` 거래가 없으면 `ORIGINAL_TX_NOT_FOUND`.
3. 원거래가 성공한 출금(`DEBIT` + `SUCCEEDED`)이 아니면 `ORIGINAL_TX_NOT_REFUNDABLE`.
4. 금액이 원출금 금액과 다르면 `AMOUNT_MISMATCH`.
5. 이미 성공한 환불이 있으면 `ALREADY_REFUNDED`.
6. 원출금 계좌 잔액을 늘리고 플랫폼 계좌 잔액을 줄인 뒤 `SUCCEEDED` 기록을 저장합니다. 플랫폼 계좌 잔액이 환불 금액보다 적으면 늘린 잔액을 되돌리고 `INSUFFICIENT_BALANCE`로 기록합니다.

- 잔액 변경 순서는 출금·환불 모두 회원 계좌 → 플랫폼 계좌로 같게 맞춰 교착을 막습니다.
- 업무 거절도 `FAILED` 거래로 기록합니다. 그래서 같은 `requestId`로 다시 물어도 같은 답을 줍니다.

### 실패 코드

| 유형 | `failureCode` | 조건 |
|---|---|---|
| 출금 | `ACCOUNT_NOT_FOUND` | 은행 코드·계좌번호의 회원 계좌가 없음 |
| 출금 | `ACCOUNT_SUSPENDED` | 계좌 상태가 `ACTIVE`가 아님 |
| 출금 | `HOLDER_MISMATCH` | 예금주 불일치(앞뒤 공백 정리 후 정확히 비교) |
| 출금 | `INSUFFICIENT_BALANCE` | 회원 계좌 잔액 < 금액 |
| 환불 | `INSUFFICIENT_BALANCE` | 플랫폼 계좌 잔액 < 환불 금액 |
| 환불 | `ORIGINAL_TX_NOT_FOUND` | 원거래 없음 |
| 환불 | `ORIGINAL_TX_NOT_REFUNDABLE` | 원거래가 성공한 출금이 아님(실패한 출금, 환불 거래 등) |
| 환불 | `AMOUNT_MISMATCH` | 금액이 원출금 금액과 다름 |
| 환불 | `ALREADY_REFUNDED` | 원거래에 성공한 환불이 이미 있음 |

지갑이 이 코드를 충전 실패 코드로 바꾸는 방식은 [지갑 가이드](wallet-service.md#실패-코드)에 있습니다.

### 원거래당 성공 환불 1회

- 원거래 하나에 `SUCCEEDED` 환불은 하나만 있을 수 있습니다(DB 부분 UNIQUE `uk_bank_transactions_refund_once`).
- 실패한 환불은 막지 않으므로 여러 건 기록될 수 있습니다.
- 같은 원거래에 서로 다른 `requestId`로 동시에 환불하면 하나만 성공합니다. 나머지는 환불 트랜잭션을 롤백한 뒤 새 트랜잭션에서 `ALREADY_REFUNDED` 기록을 남기고 200 `FAILED`로 응답합니다.

### 응답

```json
{
  "success": true,
  "data": {
    "bankTxId": "c92b2030-…",
    "requestId": "6dda1ae2-a470-4f32-84f9-8edaa4404ec5",
    "type": "DEBIT",
    "status": "SUCCEEDED",
    "failureCode": null,
    "amount": 10000,
    "originalTxId": null,
    "createdAt": "2026-11-05T01:02:03.123456Z",
    "completedAt": "2026-11-05T01:02:03.123456Z"
  },
  "error": null
}
```

| 필드 | 내용 |
|---|---|
| `bankTxId` | 은행 거래 ID. 환불 요청의 `originalTxId`에 넣는 값 |
| `requestId` | 요청의 멱등 키 |
| `type` | `DEBIT`(출금) 또는 `REFUND`(환불) |
| `status` | `SUCCEEDED` 또는 `FAILED` |
| `failureCode` | `FAILED`일 때만 값이 있음 |
| `amount` | 요청 금액 |
| `originalTxId` | 환불만. 원거래가 없어 실패한 환불은 `null` |
| `createdAt`·`completedAt` | 동기 처리라 같은 값 |

계좌 ID·계좌번호·예금주는 응답에 없습니다.

### 멱등성

`requestId`가 멱등 키입니다. 헤더는 받지 않습니다.

| 상황 | 결과 |
|---|---|
| 처음 요청, 성공 | 201 `SUCCEEDED` |
| 처음 요청, 업무 거절 | 200 `FAILED`(실패 코드). 기록됨 |
| 같은 `requestId`·같은 유형·같은 내용 재요청 | 200, 기존 기록 그대로(`SUCCEEDED`든 `FAILED`든) |
| 같은 `requestId`를 다른 유형에 사용(출금 ID로 환불 등) | 409 `BANK_REQUEST_ID_CONFLICT` |
| 같은 `requestId`·같은 유형인데 내용이 다름 | 409 `BANK_REQUEST_ID_CONFLICT` |

- 재요청은 다시 판정하지 않습니다. 거절된 `requestId`는 잔액을 채워도 계속 같은 `FAILED`를 돌려주므로 새 시도에는 새 `requestId`가 필요합니다.
- 같은 내용인지는 거래 기록에 저장된 값으로 비교합니다.
  - 출금: 금액, 그리고 `bankCode`+`accountNumber`로 찾은 회원 계좌가 기록의 출금 계좌와 같은지. 계좌 없음으로 거절된 기록은 지금도 회원 계좌를 찾지 못하면 같은 계좌로 봅니다.
  - 환불: 금액, 그리고 `originalTxId`가 기록의 원거래와 같은지. 원거래 없음으로 거절된 기록은 지금도 그 거래가 없으면 같은 원거래로 봅니다.
  - 예금주는 기록하지 않으므로 비교하지 않습니다. 계좌 없음으로 거절된 기록은 없는 계좌번호끼리 구분하지 못합니다.
- 중복 방지는 DB `UNIQUE(request_id)`로 보장합니다.

### 응답 코드와 호출 쪽 처리 권장

| 응답 | 의미 | 호출 쪽 처리 |
|---|---|---|
| 201 | 새로 성공 | 성공 처리 |
| 200 `SUCCEEDED` | 이미 성공한 기록 | 201과 같이 성공 처리 |
| 200 `FAILED` | 업무 거절 확정. 자금 이동 없음 | 실패로 확정. 같은 `requestId` 재전송은 같은 결과 |
| 409 `BANK_REQUEST_ID_CONFLICT` | `requestId`가 다른 유형 또는 다른 내용의 거래에 이미 쓰임 | 자동 재시도 중단, ERROR 로그 |
| 400 | 요청 형식 오류 | 재시도해도 같음. 자동 재시도 중단 |
| 500 | 예상하지 못한 오류 또는 `LOST` 장애 주입(헤더·유실 비율) | 결과 모름. 같은 `requestId`로 재전송하거나 [단건 조회](#단건-조회)로 확인 |
| 읽기 타임아웃·연결 실패 | 은행이 처리했을 수 있음 | 실패로 단정하지 않음. 같은 `requestId` 재전송(한 번만 처리됨) 또는 단건 조회 |

지갑의 실제 분류·복구는 [지갑 가이드](wallet-service.md#결과-모름과-복구)에 있습니다.

## 조회

조회 API는 거래를 만들거나 바꾸지 않습니다. `MANUAL_REVIEW` 조사와 충전 대사에 씁니다.

| 구분 | 형식 | 용도 |
|---|---|---|
| 단건 | 경로 `GET /internal/v1/bank/transactions/{requestId}` | 특정 요청의 결과 확인 |
| 목록 | 쿼리 `GET /internal/v1/bank/transactions?from=&to=` | 기간의 거래 전체 확인(대사) |

### 단건 조회

- 응답 `data`는 [출금·환불 응답](#응답)과 같은 형식입니다. 출금·환불·`FAILED` 기록 모두 조회됩니다.
- 기록이 없으면 404 `BANK_TRANSACTION_NOT_FOUND`.

### 404의 의미

- 404는 **조회한 시점에 그 `requestId` 기록이 없다**는 뜻입니다. 출금이 실패했다는 뜻이 아닙니다. 요청이 아직 도착하지 않았거나 처리 중일 수 있습니다.
- 조사·대사에는 조회 API만 씁니다. 결과를 확인하려고 출금을 다시 보내지 않습니다. 기록이 없을 때 출금을 보내면 그 자리에서 새 출금이 처리됩니다.

### 기간 목록

`GET /internal/v1/bank/transactions?from=2026-11-01T00:00:00Z&to=2026-11-02T00:00:00Z&page=0&size=100`

| 파라미터 | 규칙 |
|---|---|
| `from` | 필수, 포함 |
| `to` | 필수, 미포함. `from`과 같으면 빈 목록, `from`보다 앞이면 400 |
| `page` | 선택. 0부터, 기본 0. 음수는 400 |
| `size` | 선택. 기본 20, 100을 넘으면 100. 1 미만은 400 |

- 범위는 `createdAt` 기준 `[from, to)`입니다. 출금·환불, `SUCCEEDED`·`FAILED`를 모두 포함합니다.
- 정렬: `createdAt` 오름차순, 같은 시각은 `bankTxId` 오름차순으로 고정입니다. `sort` 파라미터는 무시합니다.
- 응답 `data`: `items`(단건과 같은 형식), `page`, `size`(보정된 값), `totalElements`, `totalPages`.
- offset 한도: 보정된 `size` 기준 `page × size`가 int 범위(2,147,483,647)를 넘으면 400입니다.
- 시각 형식: ISO-8601 시각에 `Z` 또는 오프셋(`+09:00`)을 붙입니다. 오프셋은 UTC로 바꿔 비교합니다. 날짜만(`2026-01-01`)이나 형식이 틀린 값은 400입니다. URL 쿼리에서 `+`는 공백으로 읽히므로 `%2B`로 인코딩합니다(예: `2026-11-01T09:00:00%2B09:00`).
- DB 시각 정밀도는 마이크로초입니다. `from`·`to`의 마이크로초 미만 값은 다음 마이크로초로 올려 조회합니다(`[from, to)` 계약은 그대로). 올림이 표현 범위를 넘으면 400. `from > to` 검사는 올리기 전 값으로 합니다.
- 페이지 사이에 거래가 추가되면 offset 방식이라 항목이 밀리거나 겹칠 수 있습니다. 대사는 이미 끝난 기간을 조회합니다.

## 시드 계좌

`V3__seed_mock_bank_accounts.sql`이 넣습니다. ID를 고정해 테스트·시연에서 같은 계좌를 가리킵니다.

| ID | 은행 코드 | 계좌번호 | 예금주 | 잔액 | 상태 | 출금 결과 |
|---|---|---|---|---|---|---|
| `…0101` | `004` | `12340101234567` | 유재석 | 3,000,000 | `ACTIVE` | 성공 |
| `…0102` | `088` | `110123456789` | 박지현 | 1,500,000 | `ACTIVE` | 성공 |
| `…0103` | `020` | `1002123456789` | 유재석 | 5,000 | `ACTIVE` | 5,000원 초과는 `INSUFFICIENT_BALANCE` |
| `…0104` | `011` | `3021234567891` | 유재석 | 2,000,000 | `SUSPENDED` | `ACCOUNT_SUSPENDED` |

- ID는 `00000000-0000-0000-0000-000000000101` 형식입니다.
- 플랫폼 계좌: `00000000-0000-0000-0000-000000000001`, `004`/`99010100000001`, `(주)트래블페이`, 잔액 0, `ACTIVE`. 출금이 들어오고 환불이 나가는 계좌입니다. 출금 계좌로 쓰면 `ACCOUNT_NOT_FOUND`입니다.
- 시드에 없는 계좌는 `ACCOUNT_NOT_FOUND`, 예금주가 다르면 `HOLDER_MISMATCH`입니다.
- 출금 성공은 회원 계좌 잔액을 줄이고 플랫폼 계좌 잔액을 늘립니다. 잔액은 DB에 남으므로 반복 테스트하면 줄어듭니다. 계좌 잔액·상태를 바꾸는 API는 없습니다.
- 충전으로 재현할 때 회원 실명 조건은 [지갑 가이드](wallet-service.md#테스트-데이터)를 봅니다.

## 장애 재현

`Mock-Fault` 요청 헤더로 응답 장애를 흉내 냅니다. 장애 주입(헤더, [출금 응답 유실 비율](#출금-응답-유실-비율), [은행별 출금 붙잡기](#은행별-출금-붙잡기))은 **기본 꺼짐**이고 두 조건을 모두 만족할 때만 동작합니다.

| 조건 | 내용 |
|---|---|
| 켜기 | `mock-bank.fault.enabled=true` (실행 인자 `--mock-bank.fault.enabled=true` 또는 환경 변수 `MOCK_BANK_FAULT_ENABLED=true`). `application.yml` 기본 `false`, 키가 없어도 꺼짐 |
| prod 아님 | prod 프로필(`SPRING_PROFILES_ACTIVE=prod`)이면 `enabled=true`여도 꺼짐 |

꺼져 있으면 장애 주입 필터·유실 비율 설정·출금 붙잡기 빈을 만들지 않습니다(붙잡기 API 경로도 없어 404). 헤더가 와도 무시하고 정상 응답하며, 유실 비율 값이 있어도 읽지도 검증하지도 않습니다(범위 밖이어도 기동). 유실 카운터도 등록되지 않습니다.

| 헤더 값 | 동작 |
|---|---|
| `LOST` | 처리·커밋 후 원래 응답을 버리고 500 `COMMON_INTERNAL_SERVER_ERROR` |
| `DELAY` | 처리·커밋 후 `mock-bank.fault.delay`만큼 기다렸다가 원래 응답 |
| 그 외 값 | 무시하고 정상 응답 |

- 값은 대소문자·앞뒤 공백을 무시합니다.
- **POST만** 대상입니다(출금·환불). GET 조회에는 헤더가 있어도 영향이 없습니다.
- 응답을 버퍼에 받아 둔 뒤 적용하므로, 장애가 나도 거래는 이미 처리·커밋됐습니다. 같은 `requestId`로 헤더 없이 다시 보내면 기존 결과(200)를 받습니다.
- 원래 응답의 상태와 관계없이 적용합니다(400·409·`FAILED`에도 적용).
- 지연 시간 설정: `mock-bank.fault.delay`(기본 `10s`, `application.yml`). `application-prod.yml`에는 키가 없어 기본값 10초를 씁니다.

```bash
# Mock 은행을 --mock-bank.fault.enabled=true로 띄운 상태
curl -i -X POST http://localhost:8090/internal/v1/bank/withdrawals \
  -H 'Content-Type: application/json' -H 'Mock-Fault: LOST' \
  -d '{"requestId":"fault-1","bankCode":"004","accountNumber":"12340101234567","holderName":"유재석","amount":10000}'
# → 500. 다시 헤더 없이 같은 본문을 보내면 200 SUCCEEDED, 잔액은 한 번만 줄어듦
```

충전 흐름에서 지갑이 첫 출금에만 이 헤더를 붙이는 방식은 [지갑 가이드](wallet-service.md#장애-주입)에 있습니다.

### 출금 응답 유실 비율

부하 측정에서 응답 유실을 일정 비율로 섞을 때 씁니다. 헤더 없이, 설정한 비율만큼 출금 응답을 처리·커밋 후 500으로 바꿉니다. 헤더 방식과 같은 [이중 조건](#장애-재현)(`mock-bank.fault.enabled=true` + prod 아님)일 때만 동작합니다(아니면 필터·설정 빈을 만들지 않으므로 값이 있어도 무시).

| 설정 | 기본 | 범위 |
|---|---|---|
| `mock-bank.fault.withdrawal-lost-rate` (환경 변수 `MOCK_BANK_FAULT_WITHDRAWAL_LOST_RATE`) | `0.0` (끔) | `0.0`~`1.0`, 범위 밖이면 기동 실패(켜져 있을 때만 검증) |

헤더 방식과 적용 범위가 다릅니다.

| | `Mock-Fault` 헤더 | 유실 비율 |
|---|---|---|
| 대상 경로 | POST 전체(출금·환불) | `POST /internal/v1/bank/withdrawals`만 |
| 대상 응답 | 원래 응답 상태와 관계없이(400·409·`FAILED` 포함) | 2xx만(성공, 업무 실패 `FAILED`). 400·409는 원래 응답 |
| 같은 `requestId` 재요청 | 헤더 없이 보내면 기존 결과(200) | **재요청에도 비율 적용.** 재시도 응답이 유실되는 상황까지 재현 |
| 동시 사용 | | 요청에 헤더가 있으면 비율 판정을 하지 않고 헤더 동작만 |

- 유실시킨 요청은 WARN 로그(`requestId`)를 남기고, 카운터 `mock.bank.fault.withdrawal.lost.rate`(Prometheus `mock_bank_fault_withdrawal_lost_rate_total`)를 올립니다. 헤더 `LOST`는 세지 않습니다.
- 응답 본문은 헤더 `LOST`와 같습니다.
- 재요청에도 적용되므로, 비율이 높으면 같은 충전이 연속으로 유실될 수 있습니다. 지갑은 결과 모름을 5회 재시도한 뒤 `MANUAL_REVIEW`로 넘깁니다(10%면 5회 연속 유실 확률 10만분의 1).
- 유실시킨 500 응답도 `http_server_requests`의 출금 경로 집계에 포함됩니다. 그래서 출금 호출 수는 재시도만큼 늘고, 실제 출금 건수는 늘지 않습니다(같은 `requestId`는 저장된 결과를 돌려주므로).

```bash
# 측정할 때 Mock 은행 실행 인자에 추가
--mock-bank.fault.enabled=true --mock-bank.fault.withdrawal-lost-rate=0.1
```

측정 절차와 비율이 실제로 적용됐는지 확인하는 방법은 [충전 부하 측정 README](../../wallet-service/load-test/README.md#장애-측정-출금-응답-유실)에 있습니다.

### 은행별 출금 붙잡기

한 은행의 출금만 응답 없이 붙잡는 장애를 실행 중에 켜고 끕니다(측정용). 은행이 연결은 받고 응답하지 않는 상황을 은행 하나에만 만들어, 다른 은행 충전이 함께 늦어지는지 봅니다. 헤더 방식과 같은 [이중 조건](#장애-재현)(`mock-bank.fault.enabled=true` + prod 아님)일 때만 API·필터가 있습니다.

| 항목 | 동작 |
|---|---|
| 대상 | `POST /internal/v1/bank/withdrawals` 중 본문 `bankCode`가 붙잡기 켜진 은행인 요청. **환불·조회는 대상 아님** |
| 붙잡는 방식 | **처리 전에** 대기 시간만큼 기다린 뒤 원래대로 처리·응답. 호출 쪽이 읽기 시간 초과로 먼저 끊으면 결과를 받지 못하지만 거래는 그 뒤에 처리·커밋됨(`docker compose pause` 후 재개와 같음). 같은 `requestId`로 다시 보내면 기존 결과 |
| 대기 시간 | API로 받음(`delaySeconds` 1~600초). 결과 모름을 만들려면 지갑 읽기 시간 초과(5초)보다 길게 씀 |
| 끄기 | 그 은행의 새 출금은 바로 처리. **기다리던 출금도 바로 풀려** 처리됨(대기 시간을 바꾸기 전부터 기다리던 출금 포함) |
| 보관 | 메모리. Mock 은행을 다시 띄우면 모두 꺼짐. 인스턴스가 여러 개면 인스턴스마다 따로 켬 |
| 다른 장애 주입과 함께 | 붙잡은 뒤 처리할 때 `Mock-Fault` 헤더·유실 비율이 그대로 적용됨 |
| 본문 이상 | 본문이 JSON이 아니거나 `bankCode`가 없으면 붙잡지 않고 원래 처리(검증 오류 400) |
| 본문 크기 | 붙잡기가 하나라도 켜져 있으면 출금 본문을 읽어 담으므로 8KiB를 넘으면 413(`Content-Length`가 없는 chunked도 읽으면서 끊음). 검증을 통과하는 가장 큰 출금 본문은 약 1.3KB |

API(세 API 모두 현재 붙잡기 목록을 은행 코드 순으로 돌려줌):

| API | 요청 | 비고 |
|---|---|---|
| `GET /internal/v1/bank/faults/withdrawal-holds` | | 켜진 은행이 없으면 `data: []` |
| `PUT /internal/v1/bank/faults/withdrawal-holds/{bankCode}` | `{"delaySeconds": 30}` | 이미 켜져 있으면 대기 시간만 바꿈(이후 출금부터 적용, 이미 기다리던 출금은 이전 대기 시간 그대로이며 끄면 함께 풀림) |
| `DELETE /internal/v1/bank/faults/withdrawal-holds/{bankCode}` | | 켜져 있지 않은 은행이어도 200 |

목록 항목:

| 필드 | 뜻 |
|---|---|
| `bankCode` | 은행 코드 |
| `delaySeconds` | 대기 시간(초) |
| `since` | 켠 시각(UTC). 대기 시간을 바꾸면 그 시각 |
| `heldCount` | 켠 뒤 붙잡은 출금 요청 수(대기 시간을 바꾸면 0부터) |

- `bankCode`가 숫자 3자리가 아니거나 `delaySeconds`가 없거나 범위 밖이면 400 `COMMON_INVALID_REQUEST`이고 상태는 바뀌지 않습니다.
- 켜고 끌 때와 붙잡을 때 WARN 로그(`출금 붙잡기 켬`·`출금 붙잡기 끔`·`출금 붙잡기`, 은행 코드·대기 시간)를 남깁니다.
- 붙잡는 동안 요청마다 Tomcat 스레드를 하나씩 잡습니다(기본 최대 200).

```bash
# Mock 은행을 --mock-bank.fault.enabled=true로 띄운 상태
curl -s -X PUT localhost:8090/internal/v1/bank/faults/withdrawal-holds/004 \
  -H 'Content-Type: application/json' -d '{"delaySeconds":30}'
# → 004 출금은 30초 뒤 처리·응답, 088 출금·환불은 바로
curl -s localhost:8090/internal/v1/bank/faults/withdrawal-holds     # heldCount 확인
curl -s -X DELETE localhost:8090/internal/v1/bank/faults/withdrawal-holds/004
```

측정 절차는 [충전 부하 측정 README](../../wallet-service/load-test/README.md#은행별-붙잡기-측정-한-은행만-멈춤)에 있습니다.

## 오류 응답

| 상태 | 코드 | 상황 |
|---|---|---|
| 400 | `COMMON_INVALID_REQUEST` | 본문 검증 실패, 필수 파라미터 누락, 시각·숫자 형식 오류, `from > to`, `page < 0`, `size < 1`, offset 한도 초과, 시각 올림 범위 초과, 출금 붙잡기 API의 은행 코드·대기 시간 오류 |
| 404 | `BANK_TRANSACTION_NOT_FOUND` | 단건 조회에서 `requestId` 기록 없음 |
| 404 | `COMMON_NOT_FOUND` | 없는 경로 |
| 409 | `BANK_REQUEST_ID_CONFLICT` | 같은 `requestId`로 다른 유형 또는 다른 내용의 거래가 이미 있음 |
| 413 | `COMMON_PAYLOAD_TOO_LARGE` | 출금 붙잡기가 켜져 있을 때 출금 본문이 8KiB 초과 |
| 500 | `COMMON_INTERNAL_SERVER_ERROR` | 예상하지 못한 오류, `Mock-Fault: LOST`, 출금 응답 유실 비율 |

업무 거절은 이 표에 없습니다. 200 + `FAILED` + [실패 코드](#실패-코드)입니다.

## DB 스키마 (Flyway)

스키마는 `mock_bank`이며 마이그레이션은 `mock-bank/src/main/resources/db/migration`에 있습니다. Hibernate는 검사만 합니다(`ddl-auto: validate`).

### 테이블

| 테이블 | 역할 | 주요 제약 |
|---|---|---|
| `bank_accounts` | 회원 계좌와 플랫폼 계좌, 잔액 | `uk_bank_accounts_number(bank_code, account_number)`, `owner_type` `USER`·`PLATFORM`, `balance >= 0`, `status` `ACTIVE`·`SUSPENDED` |
| `bank_transactions` | 출금·환불 기록(업무 거절도 `FAILED`로) | `uk_bank_transactions_request(request_id)` 멱등 키, `type` `DEBIT`·`REFUND`, `status` `SUCCEEDED`·`FAILED`, `amount > 0`, `FAILED`일 때만 `failure_code`, `SUCCEEDED`는 양쪽 계좌 필수, 출금은 `original_tx_id` 없음, 성공 환불은 `original_tx_id` 필수(V2), 계좌·원거래 FK |

| 인덱스 | 내용 |
|---|---|
| `uk_bank_accounts_number` | `(bank_code, account_number)` UNIQUE. 출금 계좌 찾기 |
| `uk_bank_transactions_request` | `(request_id)` UNIQUE. 멱등·단건 조회 |
| `uk_bank_transactions_refund_once` | `(original_tx_id) WHERE type = 'REFUND' AND status = 'SUCCEEDED'`. 원거래당 성공 환불 1회 |

- 계좌 없음으로 거절된 출금은 `from_account_id`가 `NULL`, 원거래 없음으로 거절된 환불은 계좌·`original_tx_id`가 모두 `NULL`입니다.
- 거래 기록은 INSERT만 합니다(엔티티 모든 컬럼 `updatable = false`). 잔액은 조건부 UPDATE로 바꿉니다.

### 마이그레이션

| 파일 | 내용 |
|---|---|
| `V1__create_mock_bank_tables.sql` | 두 테이블, 제약, `refund_once` 부분 UNIQUE |
| `V2__add_refund_original_check.sql` | 성공 환불은 원거래 필수 CHECK |
| `V3__seed_mock_bank_accounts.sql` | 플랫폼 계좌 1개, 회원 계좌 4개 |

이미 적용된 파일은 고치지 않고 새 파일을 추가합니다.

## 테스트

```bash
./gradlew :mock-bank:test
```

Testcontainers 통합 테스트는 PostgreSQL 컨테이너(`postgres:18.6-bookworm`)를 띄우고 Flyway를 적용해 실행합니다. `.env`나 로컬 compose DB는 필요 없고 Docker만 실행 중이면 됩니다. Docker가 없으면 건너뜁니다(통과가 아님).

| 테스트 | 범위 |
|---|---|
| `BankTransactionApiIntegrationTest` | `enabled=true`. 같은 `requestId` 동시 출금 1회 처리, 업무 거절 `FAILED` 기록·재요청 같은 결과, 예금주 공백 정리, `DELAY`·`LOST`(커밋 후 적용, 재요청은 기존 결과. 지연은 `1s`로 줄여 실행), 형식 오류 400, 다른 유형 `requestId` 409, 같은 `requestId` 다른 내용(출금 금액·계좌, 환불 금액·원거래, 계좌 없음·원거래 없음 거절 포함) 409·같은 내용 200, 동시 환불 1건만 성공·나머지 `ALREADY_REFUNDED`, 환불 재요청·업무 거절, 플랫폼 잔액 부족 환불 `INSUFFICIENT_BALANCE`(잔액 그대로), 같은 계좌 출금·환불 동시 처리 교착 없음, 단건 조회 |
| `BankFaultInjectionProdProfileTest` | `application-prod.yml` + prod 프로필 + `enabled=true`에서 `Mock-Fault: LOST`를 무시하고 201, 장애 주입 필터 빈 없음, 출금 붙잡기 빈 없음·API 404 |
| `BankFaultInjectionDisabledByDefaultTest` | `enabled` 지정 없음(`application.yml` 기본 false) + 유실 비율 1.0: `Mock-Fault: LOST`·비율 모두 무시하고 201, 필터·설정·출금 붙잡기 빈 없음, 붙잡기 API 404 |
| `BankFaultEnabledConditionTest` | Docker 불필요. `enabled` 키 없음·`false`·그 외 값이면 빈 없음(범위 밖 비율도 기동), `true`면 빈 생성(출금 붙잡기 포함)·범위 밖 비율 기동 실패 |
| `BankWithdrawalHoldIntegrationTest` | `enabled=true`. 켠 은행 출금은 대기 중 응답 없음(클라이언트 1초 시간 초과)·기록 없음 → 대기 뒤 처리·커밋, 대기보다 오래 기다리면 원래 201, 다른 은행 출금·환불은 바로 201, 끄면 기다리던 출금이 풀리고 새 출금은 바로 201, 대기 시간을 바꾼 뒤 끄면 바꾸기 전부터 기다리던 출금도 풀림(바꾼 뒤 출금은 새 대기 시간), 8KiB 넘는 출금 본문(Content-Length·chunked) 413·정상 chunked 출금 201, 목록(은행 코드 순·대기 시간 변경·`heldCount`·끄지 않은 은행 끄기 200), 잘못된 은행 코드·대기 시간 400(상태 불변), 붙잡은 뒤에도 본문을 다시 읽어 검증 오류 400 |
| `BankTransactionQueryApiIntegrationTest` | 단건 필드·환불 원거래·실패 코드, 404, 반복 조회해도 거래 불변, `[from, to)` 경계, 빈 목록, 같은 시각 ID 정렬, 페이지 이어 받기, 오프셋 시각 UTC 해석, `sort` 무시, 입력 오류 400, `size` 기본 20·최대 100, offset 한도, 마이크로초 올림, 목록 응답에 계좌 정보 없음 |
| `BankWithdrawalLostRateIntegrationTest` | `enabled=true`, 비율 1.0: 성공·`FAILED` 모두 500(본문은 헤더 `LOST`와 같음), 단건 조회로 저장 확인, 같은 `requestId` 재요청도 500, 400·409는 원래 응답, 헤더 `DELAY`·`LOST`는 기존 동작이며 카운터 증가 없음, 환불 미적용, 카운터는 유실 건수만큼 증가 |
| `BankWithdrawalLostRatePropertiesTest` | Docker 불필요. -0.1·1.1 기동 실패, 0·1.0 기동 성공, 비율 0이면 필터를 건너뜀, 헤더 요청·환불·조회에는 미적용 |
| `BankWithdrawalLostRateProdProfileTest` | prod 프로필 + `enabled=true`에서 범위 밖 값(5)이어도 기동, 출금 201, 필터·설정 빈 없음 |

## 고려 중

아래 항목은 코드에서 확인되는 미완 부분입니다. 정해지면 해당 절로 옮깁니다.

| 항목 | 현재 동작 | 정할 것 |
|---|---|---|
| `created_at` 인덱스 | `bank_transactions`에 `created_at` 인덱스가 없음. 기간 목록은 인덱스 없이 조회함 | 데이터 규모에 따라 `(created_at, id)` 인덱스 추가 |
| 계좌 관리 | 잔액·상태를 바꾸는 API 없음(시드 SQL로만 준비) | 테스트 데이터 초기화·추가 방법 |
