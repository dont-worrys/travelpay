# 송금 실행·사용 가이드

송금은 별도 서비스가 아니라 payment-service 안의 기능입니다(`com.travelpay.payment.transfer`). 회원 사이의 같은 통화 지갑 송금을 접수하고, 원장(ledger-service)에 거래를 등록해 실제 자금을 옮기며, 송금 상태와 내역을 관리합니다. 잔액은 송금이 직접 바꾸지 않고 원장이 차감·입금을 한 거래로 처리합니다. 브라우저는 Gateway를 통해 호출합니다([Gateway 가이드](gateway.md)).

## 로컬 실행

필요한 것: JDK 21, Docker.

1. `.env.example`을 `.env`로 복사하고 비밀번호 값을 바꿉니다. 실제 값은 저장소에 올리지 않습니다.
2. DB를 실행합니다.

   ```bash
   docker compose up -d postgres
   ```

3. 송금은 다른 서비스를 호출하므로 아래 서비스가 함께 떠 있어야 합니다. 모두 Eureka(`8761`)에 등록된 이름으로 찾습니다.

   | 서비스 | 포트 | 송금에서 쓰는 곳 |
   |---|---|---|
   | eureka-server | 8761 | 서비스 찾기 |
   | user-service | 8081 | 수취인 회원 확인(I02) |
   | wallet-service | 8082 | 송금인·수취인 지갑 조회 |
   | ledger-service | 8085 | 원장 거래 등록(실제 자금 이동) |

4. Spring Boot는 `.env`를 직접 읽지 않으므로 환경 변수로 넘겨 실행합니다.

   ```bash
   set -a; source .env; set +a
   ./gradlew :payment-service:bootRun
   ```

   - 로컬 포트는 `8083`입니다. `GET http://localhost:8083/actuator/health`로 상태를 확인합니다.
   - `PAYMENT_DB_URL`이 비어 있으면 `'url' must start with "jdbc"`로 시작을 멈춥니다. `.env`를 불러오지 않은 터미널에서 실행한 경우입니다.
   - Eureka 대신 원장·회원 서비스를 직접 주소로 부를 때는 `LEDGER_SERVICE_URL`·`USER_SERVICE_URL`을 지정합니다(예: `http://localhost:8085`). 비우면 Eureka에서 찾습니다.
   - 배포 설정(`application-prod.yml`)은 `SPRING_CONFIG_NAME=application-prod`로 선택합니다.

5. 테이블은 앱이 시작할 때 Flyway가 만듭니다([DB 스키마](#db-스키마-flyway)).

API 문서는 로컬에서 Swagger UI `http://localhost:8083/swagger-ui.html`의 `transfer` 그룹(JSON `/v3/api-docs/transfer`)으로 볼 수 있습니다. 배포 설정에서는 꺼져 있습니다. Gateway 없이 직접 호출할 때는 Swagger에 표시된 `User-Id`·`User-Role` 헤더를 채웁니다.

## API 목록

| API | 설명 | 로그인 | CSRF 헤더 | `Idempotency-Key` | 성공 |
|---|---|---|---|---|---|
| `POST /api/v1/transfers` | 송금 요청 | 필요 | 필요 | 필수 | 201·202 |
| `GET /api/v1/transfers/{transferId}` | 송금 상세 조회 | 필요 | 불필요 | 없음 | 200 |
| `GET /api/v1/transfers` | 송금 내역 조회 | 필요 | 불필요 | 없음 | 200 |
| `GET /api/v1/transfers/recipients/{receiverId}` | 수취인 확인 | 필요 | 불필요 | 없음 | 200 |
| `POST /api/v1/transfers/{transferId}/cancel` | 송금 취소 | 필요 | 필요 | 없음 | 200 |

응답 본문은 `success`·`data`·`error` 공통 형식입니다([error-contract.md](error-contract.md)). ID는 모두 UUID이고, 금액은 통화별 최소 단위 정수(KRW 원, JPY 엔, CNY 펀)이며, 시각은 UTC ISO-8601입니다.

로그인·CSRF는 Gateway가 확인하고, payment-service는 Gateway가 넣어 준 `User-Id`·`User-Role` 헤더로 요청자를 정합니다. 요청 본문에 송금인 ID를 넣어도 쓰지 않습니다. 헤더가 없거나 일부만 있거나 형식이 잘못되면 `401 COMMON_UNAUTHORIZED`입니다.

## 호출 순서

로그인과 CSRF 토큰 받기는 [User 가이드](user-service.md#호출-순서)와 같습니다. 송금 화면에서는 아래 순서로 호출합니다.

```txt
1. GET  /api/v1/transfers/recipients/{receiverId} → 마스킹 이름과 송금 가능 여부 확인
2. POST /api/v1/transfers                         → Csrf-Token·Idempotency-Key 헤더 포함
   - 201 COMPLETED: 송금 완료
   - 202 PROCESSING: 결과 확인 중. 서버가 자동으로 다시 처리하므로 3번으로 상태 확인
   - 422: 잔액 부족 등으로 거절(송금은 FAILED로 기록)
3. GET  /api/v1/transfers/{transferId}            → 최종 상태 확인
4. GET  /api/v1/transfers?direction=SENT          → 보낸 내역
```

응답을 받지 못해 다시 보낼 때는 **같은 `Idempotency-Key`와 같은 본문**을 사용합니다. 그래야 송금이 두 번 되지 않습니다. 새 송금마다 새 키를 만듭니다(UUID 권장).

curl로 Gateway 없이 확인할 때는 회원 헤더를 직접 넣습니다. 아래 ID는 예시입니다.

```bash
BASE=http://localhost:8083
SENDER=550e8400-e29b-41d4-a716-446655440000
RECEIVER=4c6a0178-266b-4f1e-b811-a2767026be70

curl -s -H "User-Id: $SENDER" -H 'User-Role: USER' \
  "$BASE/api/v1/transfers/recipients/$RECEIVER"

curl -s -H "User-Id: $SENDER" -H 'User-Role: USER' \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d "{\"receiverId\":\"$RECEIVER\",\"amount\":10000,\"currency\":\"KRW\",\"memo\":\"저녁값\"}" \
  "$BASE/api/v1/transfers"

curl -s -H "User-Id: $SENDER" -H 'User-Role: USER' \
  "$BASE/api/v1/transfers?direction=SENT&page=0&size=20"
```

Git Bash에서 한글이 든 본문을 인자로 넘기면 글자가 깨져 400이 날 수 있습니다. 이때는 UTF-8 파일로 저장해 `--data-binary @body.json`으로 보냅니다.

## 송금 요청

### 요청

| 항목 | 규칙 |
|---|---|
| `Idempotency-Key` 헤더 | 필수. 공백 없는 ASCII 1~100자. 없거나 형식이 틀리면 400 `TRANSFER_INVALID_IDEMPOTENCY_KEY` |
| `receiverId` | 필수, UUID. 본인이면 400 `TRANSFER_SELF_TRANSFER` |
| `amount` | 필수, 0보다 큰 정수(통화별 최소 단위) |
| `currency` | 필수, `KRW`·`JPY`·`CNY`. 형식이 틀리면 400 `COMMON_INVALID_REQUEST`, 지원하지 않는 통화면 400 `TRANSFER_UNSUPPORTED_CURRENCY` |
| `memo` | 선택, 100자 이하. 앞뒤 공백은 지우고 빈 값은 없는 것으로 봅니다 |

```json
{
  "receiverId": "4c6a0178-266b-4f1e-b811-a2767026be70",
  "amount": 10000,
  "currency": "KRW",
  "memo": "저녁값"
}
```

### 처리 순서

1. 같은 송금인·같은 키의 송금이 이미 있으면 아래 [멱등성](#멱등성) 규칙으로 응답합니다.
2. 본인 송금·지원 통화를 확인합니다.
3. 송금인 지갑이 `ACTIVE`인지 확인합니다. 아니면 422 `TRANSFER_SENDER_WALLET_UNAVAILABLE`.
4. 수취인이 `ACTIVE` 회원인지(user-service I02), 수취인 지갑이 `ACTIVE`인지 확인합니다. 아니면 422 `TRANSFER_RECEIVER_UNAVAILABLE`.
5. 송금을 `PROCESSING`으로 저장(커밋)한 뒤 원장에 거래를 등록합니다. 원장 호출 중에는 DB 트랜잭션을 잡지 않습니다.
6. 원장 결과로 상태를 정하고 응답합니다([원장 연동](#원장-연동과-복구)).

회원당 지갑은 하나이고, 통화별 계정은 원장이 `walletId + currency`로 찾습니다. 지갑·회원 확인은 빠른 거절용이며 최종 판단(잔액, 계정 상태)은 원장 거래 등록 결과입니다.

### 응답

| 상태 | HTTP | 의미 |
|---|---|---|
| `COMPLETED` | 201 | 원장 반영 완료, 송금인 차감·수취인 입금 끝 |
| `PROCESSING` | 202 | 원장 결과를 확인하지 못함. 서버가 자동으로 다시 처리 |
| `MANUAL_REVIEW` | 202 | 자동 처리를 멈춤. 운영자 확인 필요 |
| `FAILED` | 422 | 원장이 거절(잔액 부족·계정 사용 불가). 오류 응답으로 반환되며 송금은 기록에 남음 |

```json
{
  "success": true,
  "data": {
    "transferId": "6dda1ae2-a470-4f32-84f9-8edaa4404ec5",
    "direction": "SENT",
    "senderId": "550e8400-e29b-41d4-a716-446655440000",
    "receiverId": "4c6a0178-266b-4f1e-b811-a2767026be70",
    "amount": 10000,
    "currency": "KRW",
    "status": "COMPLETED",
    "memo": "저녁값",
    "createdAt": "2026-11-02T01:02:03.123456Z"
  },
  "error": null
}
```

응답에는 지갑 ID·멱등성 키·실패 사유·수정 시각을 담지 않습니다. `direction`은 요청자 기준으로 `SENT`(보낸 송금) 또는 `RECEIVED`(받은 송금)입니다.

### 멱등성

| 상황 | 결과 |
|---|---|
| 같은 키·같은 본문, 송금 `COMPLETED` | 201, 같은 `transferId` |
| 같은 키·같은 본문, 송금 `FAILED` | 처음과 같은 422 |
| 같은 키·같은 본문, 송금 `PROCESSING`·`MANUAL_REVIEW` | 202, 현재 상태 |
| 같은 키·다른 본문 | 409 `TRANSFER_IDEMPOTENCY_KEY_REUSED` |
| 같은 키 동시 요청 | 하나만 저장되고 나머지는 위 규칙으로 응답 |

- 같은 본문인지는 `receiverId`·`amount`·`currency`·정규화한 `memo`의 SHA-256으로 비교합니다.
- 재요청은 원장을 다시 호출하지 않고 지갑·회원도 다시 조회하지 않습니다. 결과 모름 송금의 재전송은 [복구 스케줄러](#복구-스케줄러)만 합니다.
- 중복 방지는 DB `UNIQUE(sender_id, idempotency_key)`로 보장합니다. 키는 송금인별로 구분합니다.

## 조회·취소

상세 조회와 취소의 `data`는 송금 요청 응답과 같은 송금 객체이고, 내역 조회의 `data`는 송금 객체 목록을 담은 페이지입니다.

### 상세 조회

송금인 또는 수취인만 조회할 수 있습니다. 그 밖의 사용자에게는 송금이 없는 것과 같이 404 `TRANSFER_NOT_FOUND`를 반환해 존재 여부를 알리지 않습니다.

### 내역 조회

| 파라미터 | 규칙 |
|---|---|
| `direction` | `SENT` 보낸 내역, `RECEIVED` 받은 내역, 생략하면 둘 다. 그 밖의 값은 400 |
| `page` | 0부터, 기본 0. 0 미만은 400 |
| `size` | 기본 20, 1 미만은 400, 100을 넘으면 100으로 조회 |

정렬은 `createdAt desc, id desc`로 고정이며 요청자가 보내거나 받은 송금만 반환합니다. 응답은 common `PageResponse`입니다.

| 필드 | 내용 |
|---|---|
| `items` | 송금 객체 목록 |
| `page` | 현재 페이지(0부터) |
| `size` | 실제 조회한 페이지 크기(100을 넘겨 요청하면 100) |
| `totalElements` | 전체 건수 |
| `totalPages` | 전체 페이지 수 |

### 수취인 확인

송금 전 화면에서 수취인을 확인하는 용도입니다. 마스킹한 이름과 송금 가능 여부만 반환하고, 지갑 ID·지갑 상태 원문은 반환하지 않습니다.

```json
{
  "success": true,
  "data": {
    "receiverId": "4c6a0178-266b-4f1e-b811-a2767026be70",
    "receiverName": "홍*동",
    "transferable": true
  },
  "error": null
}
```

| 상황 | 결과 |
|---|---|
| 본인 ID | 400 `TRANSFER_SELF_TRANSFER` |
| 회원 없음·정지·종료 | 404 `TRANSFER_RECEIVER_NOT_FOUND` (지갑은 조회하지 않음) |
| 지갑 없음 | 404 `TRANSFER_RECEIVER_NOT_FOUND` |
| 지갑이 `ACTIVE`가 아님 | 200 `transferable: false` |
| 지갑 `ACTIVE` | 200 `transferable: true` |
| user-service 조회 한도 초과 | 429 `COMMON_TOO_MANY_REQUESTS` |

이름 마스킹은 1자 `*`, 2자 `홍*`, 3자 이상은 첫 글자와 끝 글자만 남깁니다(`홍*동`, `남**수`). 가운데 띄어쓰기도 가립니다. 확인 결과는 참고용이며 실제 송금 때 다시 검증합니다.

user-service I02는 요청자별 호출 한도(기본 60초 60회)가 있습니다. 송금 요청과 수취인 확인이 같은 한도를 씁니다([User 가이드](user-service.md#i02-요청자별-호출-제한)).

### 취소

| 상황 | 결과 |
|---|---|
| 송금인이 `REQUESTED` 송금 취소 | 200 `CANCELLED` |
| 이미 `CANCELLED` | 200, 현재 상태 그대로 |
| `PROCESSING`·`COMPLETED`·`FAILED`·`MANUAL_REVIEW` | 409 `COMMON_CONFLICT` |
| 수취인·제3자·없는 송금 | 404 `TRANSFER_NOT_FOUND` |

원장 연동 후 새 송금은 `PROCESSING`으로 시작하므로 취소할 수 있는 송금은 연동 전에 접수된 `REQUESTED` 송금뿐입니다. 취소는 조건부 UPDATE로 처리해 동시 요청에도 한 번만 바뀝니다.

## 관리자 조회

| API | 설명 | 성공 |
|---|---|---|
| `GET /api/v1/admin/transfers` | 모든 사용자의 송금 목록 | 200 |
| `GET /api/v1/admin/transfers/{transferId}` | 송금 상세(당사자 제한 없음) | 200 |
| `POST /api/v1/admin/transfers/{transferId}/resolve` | `MANUAL_REVIEW` 송금 확정 | 200 |
| `GET /api/v1/admin/transfers/{transferId}/history` | 상태 변경 이력 | 200 |

- 권한: `User-Role`이 `MASTER`가 아니면 `403 COMMON_FORBIDDEN`. 경로의 `admin`은 관리 기능 영역, `MASTER`는 허용 역할입니다.
- 목록 조건(모두 선택): `from`(포함)·`to`(미포함, ISO-8601, `from`이 `to`보다 앞서야 함), `currency`(KRW·JPY·CNY, 그 밖은 `400 TRANSFER_UNSUPPORTED_CURRENCY`), `status`, `userId`(송금인 또는 수취인), `orderType`(`LATEST` 기본 = 생성 시각 내림차순, `OLDEST` = 오름차순, 같은 시각은 ID로 같은 방향), `page`(0부터)·`size`(기본 20, 최대 100). 잘못된 값은 `400 COMMON_INVALID_REQUEST`
- 응답: `transferId`, `senderId`, `receiverId`, `sourceWalletId`, `targetWalletId`, `amount`, `currency`, `status`, `failureReason`(`FAILED`만), `reviewReason`(`MANUAL_REVIEW`만), `attemptCount`, `memo`, `createdAt`, `updatedAt`. `direction`·멱등성 키는 없음. 없는 송금은 `404 TRANSFER_NOT_FOUND`
- 조회 기록: 관리자 ID와 조건(목록)·송금 ID(상세)를 INFO 로그로 남깁니다.
- Swagger 그룹: `transfer-admin`(`/v3/api-docs/transfer-admin`)

### MANUAL_REVIEW 확정

요청 본문 `{targetStatus, reason(필수, ≤255자), ledgerTransactionId}`. 운영자 입력을 그대로 믿지 않고, 서버가 원장 `GET /internal/v1/ledger/transactions?referenceType=TRANSFER&referenceId={송금 ID}`로 결과를 조회해 맞는 확정만 반영합니다.

| 원장 결과 | 허용하는 `targetStatus` | 결과 |
|---|---|---|
| `POSTED` + 원장 항목이 송금과 같음 | `COMPLETED`(`ledgerTransactionId` = 원장 거래 ID) | `COMPLETED` |
| `REJECTED` | `FAILED`(`ledgerTransactionId` = 원장 거래 ID) | `FAILED`, 실패 사유는 원장 거절 사유(`INSUFFICIENT_BALANCE` / 그 밖 `ACCOUNT_UNAVAILABLE`) |
| 기록 없음(404 `LEDGER_TRANSACTION_NOT_FOUND`) | `PROCESSING`(`ledgerTransactionId` 없음) | 시도 횟수 0으로 되돌려 복구 스케줄러가 약 30초 뒤 같은 reference로 재전송 |

- 맞지 않으면 `409 TRANSFER_RESOLUTION_MISMATCH`, `MANUAL_REVIEW`가 아니면 `409 COMMON_CONFLICT`. 형식 오류는 `400 COMMON_INVALID_REQUEST`
- 이미 확정된 송금에 같은 운영자가 같은 본문(`targetStatus`·`reason`·`ledgerTransactionId`)으로 다시 요청하면(응답 유실 후 재시도) 원장을 다시 조회하지 않고 현재 송금 상태를 200으로 돌려줍니다. 마지막 운영자 확정 이력과 비교하며, 다른 운영자·다른 내용이면 `409 COMMON_CONFLICT`입니다. `Idempotency-Key`는 받지 않습니다(자금을 직접 옮기지 않고 조건부 UPDATE로 한 번만 반영됨)
- 원장 `POSTED`이면 원장 조회 응답의 `entries`를 이 송금의 원장 요청 항목(송금인 지갑 DEBIT `TRANSFER_OUT`, 수취인 지갑 CREDIT `TRANSFER_IN`, 같은 통화·금액)과 비교합니다. 순서는 보지 않고, 항목이 빠지거나 더 있거나 값이 하나라도 다르면 `409 TRANSFER_RESOLUTION_MISMATCH`입니다(`reviewReason`과 관계없이). `POSTED`인데 항목이 없거나 값이 비어 있으면 `502`
- `reviewReason=LEDGER_REQUEST_NOT_ACCEPTED`(원장 409·400) 송금은 원장 `POSTED`이고 항목이 같을 때만 `COMPLETED`로 확정합니다. 원장 `REJECTED`는 원장이 항목을 남기지 않아 같은 내용의 거절인지 확인할 수 없으므로 확정하지 않습니다(409)
- 원장 조회 실패는 상태를 바꾸지 않고 503·504·502로 응답합니다
- 상태 변경은 `MANUAL_REVIEW`일 때만 바꾸는 조건부 UPDATE이며, 같은 트랜잭션에서 이력(`transfer_status_history`: 이전·이후 상태, 사유, 운영자 ID, 원장 거래 ID, 시각)을 저장합니다. 동시에 확정하면 한 건만 성공합니다
- 확정 뒤 송금인의 같은 키 재요청은 확정된 상태로 응답합니다(`COMPLETED` 201, `FAILED` 처음과 같은 사유의 422)
- Gateway `/api/v1/admin/transfers/**`는 payment-service로 라우팅됩니다(#155). 외부 호출은 MASTER 세션 쿠키, 확정(POST)은 CSRF가 필요합니다.

## 송금 상태

```txt
REQUESTED ──취소──▶ CANCELLED            (원장 연동 전 접수 건만)

PROCESSING ──원장 반영──▶ COMPLETED
           ──원장 거절──▶ FAILED
           ──결과 모름 5회 / 원장이 요청을 받지 않음──▶ MANUAL_REVIEW
```

| 상태 | 의미 |
|---|---|
| `REQUESTED` | 원장 연동 전에 접수만 된 송금. 자금 이동 없음, 취소 가능 |
| `PROCESSING` | 원장 처리 결과 미확정. 복구 스케줄러가 다시 요청 |
| `COMPLETED` | 자금 이동 완료 |
| `FAILED` | 원장 거절. 실패 사유(`INSUFFICIENT_BALANCE`·`ACCOUNT_UNAVAILABLE`)를 DB에 저장 |
| `CANCELLED` | 접수 취소 |
| `MANUAL_REVIEW` | 자동 처리 중단. 사유(`RETRY_EXHAUSTED`·`LEDGER_REQUEST_NOT_ACCEPTED`)를 DB에 저장 |

## 원장 연동과 복구

### 원장 거래 등록

`POST /internal/v1/ledger/transactions`에 송금 하나를 한 거래로 보냅니다. 원장이 두 줄을 함께 반영하거나 함께 거절하므로 한쪽만 반영되지 않습니다.

| 항목 | 값 |
|---|---|
| reference | `referenceType=TRANSFER`, `referenceId=송금 ID`. 원장의 중복 처리 방지 기준 |
| `userId` | 송금인 ID(기록용, 계정 조회에 쓰지 않음) |
| 송금인 줄 | `USER`, 송금인 `walletId`, 통화, `DEBIT`, 금액, `TRANSFER_OUT` |
| 수취인 줄 | `USER`, 수취인 `walletId`, 통화, `CREDIT`, 금액, `TRANSFER_IN` |

`TRANSFER_OUT`·`TRANSFER_IN`은 원장 entry_type 허용 목록에 있는 값입니다.

요청은 저장된 송금 값으로만 만들기 때문에 재전송도 처음과 같은 내용입니다. 원장은 같은 reference를 한 번만 처리하므로 재전송해도 잔액은 한 번만 바뀝니다. `memo`는 원장에 보내지 않습니다.

| 원장 응답 | 송금 상태 | 클라이언트 응답 |
|---|---|---|
| 201·200 `POSTED` | `COMPLETED` | 201 |
| 422 `LEDGER_REJECTED_INSUFFICIENT_BALANCE` | `FAILED` | 422 `TRANSFER_INSUFFICIENT_BALANCE` |
| 422 `LEDGER_REJECTED_WALLET_SUSPENDED`·`_ACCOUNT_NOT_FOUND` | `FAILED` | 422 `TRANSFER_ACCOUNT_UNAVAILABLE` |
| 읽기 타임아웃·연결 실패·Eureka 미등록·5xx·해석 불가 응답 | `PROCESSING` 유지(결과 모름) | 202 |
| 409·400·알 수 없는 422 코드·그 밖의 4xx | `MANUAL_REVIEW` | 202 |

- 타임아웃이나 5xx는 원장이 이미 처리했을 수 있으므로 실패로 단정하지 않습니다.
- 409는 같은 reference의 원장 거래가 이미 있다는 뜻일 수 있어 `FAILED`로 끝내지 않습니다. 400·409는 송금 요청을 잘못 만든 버그나 계약 불일치로 보고 ERROR 로그를 남깁니다.
- 원장 Feign 호출은 자동 재시도를 끕니다. 타임아웃은 연결 1초, 읽기 6초입니다(원장 최대 응답 약 5초).

### 복구 스케줄러

`TransferRecoveryScheduler`가 결과 모름으로 남은 `PROCESSING` 송금을 같은 reference로 원장에 다시 보냅니다. 지갑 충전 복구와 같은 방식입니다.

| 항목 | 값 |
|---|---|
| 주기 | 10초마다(시작 10초 후부터) |
| 한 주기 처리량 | 재시도 시각이 지난 송금을 수정 시각 오래된 순으로 최대 10건 |
| 재시도 간격 | 결과 모름 횟수가 n이면 30초 × 2^(n−1) → 30초, 1분, 2분, 4분 |
| 한도 | 결과 모름 5회(첫 요청 포함)면 `MANUAL_REVIEW`(`RETRY_EXHAUSTED`) |
| 원장 장애 판단 | 한 주기에서 결과 모름이 연달아 3번이면 남은 건은 다음 주기로 |
| 끄기 | `transfer.recovery.enabled=false` (테스트·수동 확인용) |

- 선점: 조회한 송금의 상태·수정 시각이 그대로일 때만 조건부 UPDATE로 가져갑니다. 여러 인스턴스가 함께 돌아도 같은 송금을 동시에 처리하지 않고, 결과 반영도 `PROCESSING`일 때만 하므로 잔액·상태는 한 번만 바뀝니다. 분산 락은 쓰지 않습니다.
- 한 건의 오류는 로그만 남기고 다음 건을 계속 처리합니다.
- payment-service의 스케줄러 스레드는 2개입니다(`spring.task.scheduling.pool.size`). 결제 정산 복구와 서로의 원장 호출을 기다리지 않게 하기 위함입니다.

| 지표 | 의미 |
|---|---|
| `transfer.processing.stuck` | 생성 후 30분이 지나도 `PROCESSING`인 송금 수. 0보다 크면 복구가 돌지 않는 것이므로 ERROR 로그(오래된 순 ID 10개)도 남김 |
| `transfer.manual_review` | 운영자 확인을 기다리는 `MANUAL_REVIEW` 송금 수 |

`MANUAL_REVIEW` 송금은 원장 reference 조회(`GET /internal/v1/ledger/transactions?referenceType=TRANSFER&referenceId=송금ID`)로 반영 여부를 확인해야 합니다. 특히 `RETRY_EXHAUSTED`는 원장에 이미 반영됐을 수 있습니다.

## 다른 서비스 호출

| 대상 | API | 송금 쪽 처리 |
|---|---|---|
| wallet-service | `GET /internal/v1/wallets?userId=` | `{walletId, status}`. `ACTIVE`만 송금 가능. 404 + `WALLET_NOT_FOUND`일 때만 지갑 없음 |
| user-service | `GET /internal/v1/users/{userId}/real-name` (I02) | 200이면 `ACTIVE` 회원. 404 + `USER_RECIPIENT_NOT_FOUND`일 때만 회원 아님. 응답 `userId`가 요청과 다르면 502 |
| ledger-service | `POST /internal/v1/ledger/transactions` | [원장 거래 등록](#원장-거래-등록) |

지갑·회원 호출은 결제와 같은 Feign 클라이언트(`WalletFeignApi`·`UserFeignApi`)를 쓰며 타임아웃은 연결 2초, 읽기 3초입니다. 사용자 요청 중의 호출에는 common `InternalHeaderRelayInterceptor`가 요청자의 `User-Id`·`User-Role`을 붙입니다. 지갑·회원 호출 실패는 common `FeignErrors.toCommonException()` 규칙을 따릅니다.

| 지갑·회원 호출 실패 | 송금 응답 |
|---|---|
| 연결 실패, Eureka 미등록, 상대 503 | 503 `COMMON_SERVICE_UNAVAILABLE` |
| 읽기 타임아웃, 상대 504 | 504 `COMMON_GATEWAY_TIMEOUT` |
| 상대 429 + `COMMON_TOO_MANY_REQUESTS` | 429 `COMMON_TOO_MANY_REQUESTS` |
| 위 목록에 없는 404·5xx, 해석할 수 없는 응답 | 502 `COMMON_BAD_GATEWAY` |

지갑·회원 확인 단계에서 실패하면 송금은 저장되지 않고 키도 사용되지 않습니다. 같은 키로 다시 요청할 수 있습니다.

## 오류 응답

| 상태 | 코드 | 상황 |
|---|---|---|
| 400 | `COMMON_INVALID_REQUEST` | 본문 검증 실패, 잘못된 `page`·`size`·`direction` |
| 400 | `TRANSFER_INVALID_IDEMPOTENCY_KEY` | `Idempotency-Key` 없음·형식 오류 |
| 400 | `TRANSFER_SELF_TRANSFER` | 본인에게 송금, 본인 수취인 확인 |
| 400 | `TRANSFER_UNSUPPORTED_CURRENCY` | KRW·JPY·CNY가 아닌 통화 |
| 401 | `COMMON_UNAUTHORIZED` | 회원 헤더 없음(Gateway 경유 시 세션 없음) |
| 403 | `COMMON_CSRF_INVALID` | Gateway 경유 변경 요청의 CSRF 토큰 없음·불일치 |
| 404 | `TRANSFER_NOT_FOUND` | 송금이 없거나 당사자가 아님(취소는 송금인이 아님) |
| 404 | `TRANSFER_RECEIVER_NOT_FOUND` | 수취인 확인에서 활성 회원이 아니거나 지갑 없음 |
| 409 | `TRANSFER_IDEMPOTENCY_KEY_REUSED` | 같은 키로 다른 본문 |
| 409 | `COMMON_CONFLICT` | 취소할 수 없는 상태 |
| 422 | `TRANSFER_SENDER_WALLET_UNAVAILABLE` | 송금인 지갑 없음·비활성 |
| 422 | `TRANSFER_RECEIVER_UNAVAILABLE` | 수취인이 활성 회원이 아니거나 지갑 없음·비활성 |
| 422 | `TRANSFER_INSUFFICIENT_BALANCE` | 원장 잔액 부족 거절(송금 `FAILED`) |
| 422 | `TRANSFER_ACCOUNT_UNAVAILABLE` | 원장 계정 정지·없음 거절(송금 `FAILED`) |
| 429 | `COMMON_TOO_MANY_REQUESTS` | user-service 수취인 조회 한도 초과 |
| 502 | `COMMON_BAD_GATEWAY` | 지갑·회원 서비스 응답 해석 불가 |
| 503 | `COMMON_SERVICE_UNAVAILABLE` | 지갑·회원 서비스 연결 불가 |
| 504 | `COMMON_GATEWAY_TIMEOUT` | 지갑·회원 서비스 응답 시간 초과 |

원장 장애는 오류가 아니라 202 `PROCESSING`으로 응답합니다. 장애 메시지나 상대 응답 본문은 클라이언트에 내보내지 않습니다.

## DB 스키마 (Flyway)

송금 테이블은 `payment_service.transfer`이며, 결제와 같은 `payment-service/src/main/resources/db/migration` 폴더를 씁니다. 파일 이름은 `V{yyyyMMdd}_{순번}__{설명}.sql`입니다. Hibernate는 검사만 합니다(`ddl-auto: validate`).

| 파일 | 내용 |
|---|---|
| `V20261028_1__create_transfer.sql` | 송금 테이블, 멱등성 UNIQUE, 금액·통화·당사자 CHECK, 목록 인덱스 |
| `V20261029_1__add_transfer_cancelled_status.sql` | `CANCELLED` 상태 |
| `V20261030_2__add_transfer_ledger_status.sql` | `PROCESSING`·`COMPLETED`·`FAILED`, `failure_reason` |
| `V20261102_1__add_transfer_manual_review.sql` | `MANUAL_REVIEW`, `attempt_count`, `review_reason`, 복구 대상 부분 인덱스 |

| 제약·인덱스 | 내용 |
|---|---|
| `uk_transfer_sender_id_idempotency_key` | `UNIQUE(sender_id, idempotency_key)` 중복 송금 방지 |
| `ck_transfer_amount` | `amount > 0` |
| `ck_transfer_currency` | 대문자 3자리 형식만 검사. 지원 통화(KRW·JPY·CNY)는 앱에서 검사 |
| `ck_transfer_sender_receiver`, `ck_transfer_source_target_wallet` | 송금인·수취인, 두 지갑이 서로 달라야 함 |
| `ck_transfer_failed_has_reason` | `FAILED`일 때만 `failure_reason`이 있음 |
| `ck_transfer_manual_review_has_reason` | `MANUAL_REVIEW`일 때만 `review_reason`이 있음 |
| `ix_transfer_sender_id_created_at`, `ix_transfer_receiver_id_created_at` | 보낸·받은 내역 목록 |
| `ix_transfer_processing_updated_at` | `PROCESSING` 송금 복구 대상 조회(부분 인덱스) |

- 회원·지갑은 다른 서비스 소유라 FK 없이 ID만 저장합니다. 송금인·수취인 ID와 두 지갑 ID를 모두 저장해 재전송 요청을 저장된 값만으로 만듭니다.
- 이미 적용한 파일은 고치지 않고 다음 날짜·순번의 새 파일을 추가합니다. 결제 쪽과 같은 날짜를 쓰면 순번이 겹치지 않게 맞춥니다.
- Flyway는 순서를 벗어난 적용을 허용하지 않습니다(`outOfOrder` 꺼짐). 다른 브랜치에서 나중 버전을 먼저 적용한 로컬 DB는 `Detected resolved migration not applied to database`로 시작을 멈춥니다. 이때 로컬에서만 한 번 `SPRING_FLYWAY_OUT_OF_ORDER=true`로 실행해 빠진 파일을 적용합니다. 설정 파일은 바꾸지 않습니다.

## 테스트

```bash
./gradlew :payment-service:test --tests "com.travelpay.payment.transfer.*"
```

- 단위 테스트는 DB 없이 실행됩니다. 원장은 테스트 안의 Mock으로 연결해 응답별 상태 처리, 재전송 때 잔액이 한 번만 바뀌는지, 복구 스케줄러 동작을 확인합니다.
- DB 통합 테스트(`TransferRepositoryIntegrationTest`, `TransferApiDocsIntegrationTest`, `integration` 패키지 등)는 Testcontainers로 PostgreSQL 컨테이너(`postgres:18.6-bookworm`)를 띄우고 Flyway 마이그레이션을 적용해 실행합니다. `.env`나 로컬 compose DB는 필요 없고 Docker만 실행 중이면 됩니다. Docker가 없으면 건너뜁니다.
  - 컨테이너는 테스트 실행마다 한 번만 띄워 여러 테스트 클래스가 함께 씁니다(`com.travelpay.payment.support.PostgresContainerSupport`). 새 DB 통합 테스트는 이 클래스를 상속합니다.
  - 테스트마다 롤백하고 복구·정산 스케줄러는 끈 상태로 돕니다.
- 커버리지 리포트(JaCoCo)는 테스트 후 `payment-service/build/reports/jacoco/test/html/index.html`에 생성됩니다.

## Gateway 연결과 신뢰 경계

- 외부 요청은 Gateway `/api/v1/transfers/**` 라우트로 들어옵니다. Gateway는 세션·CSRF를 확인하고, 클라이언트가 보낸 `User-Id`·`User-Role`을 지운 뒤 검증한 값으로 다시 설정합니다. 위조한 헤더는 무시되고 세션 회원으로 처리됩니다.
- payment-service는 회원 헤더를 믿고 요청자를 정하므로, 배포에서는 payment-service 포트를 외부에 열지 않아야 합니다. 직접 접근할 수 있으면 헤더를 위조할 수 있습니다.
- 수취인 확인의 대량 조회는 user-service I02의 요청자별 한도로 막습니다.

## 고려 중

아래 항목은 아직 정해지지 않았습니다. 정해지면 해당 절로 옮깁니다.

| 항목 | 현재 동작 | 정할 것 |
|---|---|---|
| 1회 최대 송금 금액 | 0보다 큰지만 검사 | 통화별(KRW·JPY·CNY 최소 단위) 한도 값과 오류 코드. 지갑·환전·결제 담당자와 함께 정함 |
| 완료된 송금 취소(자금 복구) | `REQUESTED`만 취소, 그 밖의 상태는 409 | 취소 가능 조건·기한, 수취인 잔액 부족 시 처리, 원장 분개(referenceType·entryType, 원거래 연결), 상태와 `Idempotency-Key` 적용 여부 |
| 상태 변경 이력 범위 | 운영자 확정만 `transfer_status_history`에 기록 | 자동 처리(원장 응답·스케줄러) 전이도 기록할지 |
| 연동 전 `REQUESTED` 송금 | 그대로 남아 있고 취소만 가능 | 그대로 둘지, 일괄 취소할지, 재처리할지 |
| 원장 연동 롤백 시 `PROCESSING` 송금 | 원장 구현이 없으면 복구가 돌지 않아 202만 반복되고 취소도 409 | 롤백할 때 남은 건의 처리 절차 |
| 잔액 사전 조회 | 하지 않음. 잔액 부족은 원장 거절로만 판단 | 원장 잔액 조회로 빠르게 거절할지 |
| `MASTER` 역할 | payment-service가 `User-Role`을 검사하지 않아 송금 API 사용 가능(wallet-service는 403) | 송금에서 차단할지 |
| 송금 API 호출 제한 | 없음 | Gateway 또는 송금 API에서 사용자 단위 제한을 둘지 |
