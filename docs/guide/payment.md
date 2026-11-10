# 결제·환불·가맹점·정산 실행·사용 가이드

payment-service의 결제 기능입니다. 회원이 가맹점에 지갑 잔액으로 결제하고 환불을 신청하면, 가맹점 주인이 신청을 거절하거나 환불을 실행하며, 환불 기간이 지난 결제는 가맹점 주인이 정산을 요청해 자기 회원 지갑으로 받습니다. 관리자(`MASTER`)는 가맹점을 만들고 자동 처리가 멈춘 결제·환불(`MANUAL_REVIEW`)을 정리합니다. 잔액은 결제가 직접 바꾸지 않고 원장(ledger-service)이 차감·입금을 한 거래로 처리합니다. 같은 서비스 안의 송금은 [송금 가이드](payment-transfer.md)를 봅니다. 브라우저는 Gateway를 통해 호출합니다([Gateway 가이드](gateway.md)).

## 로컬 실행

필요한 것: JDK 21, Docker.

1. `.env.example`을 `.env`로 복사하고 비밀번호 값을 바꿉니다. 실제 값은 저장소에 올리지 않습니다.
2. DB를 실행합니다.

   ```bash
   docker compose up -d postgres
   ```

3. 결제는 다른 서비스를 호출하므로 아래 서비스가 함께 떠 있어야 합니다. 모두 Eureka(`8761`)에 등록된 이름으로 찾습니다.

   | 서비스 | 포트 | 결제에서 쓰는 곳 |
   |---|---|---|
   | eureka-server | 8761 | 서비스 찾기 |
   | user-service | 8081 | 가맹점 주인 회원 확인(I02) |
   | wallet-service | 8082 | 결제자 지갑 조회, 정산 받을 가맹점 주인 지갑 조회 |
   | ledger-service | 8085 | 가맹점 원장 계정 생성·잔액 조회, 결제·환불·정산 거래 등록 |

   결제자 지갑에 잔액을 넣으려면(충전) wallet-service가 mock-bank를 호출하므로 mock-bank도 함께 띄웁니다.

4. Spring Boot는 `.env`를 직접 읽지 않으므로 환경 변수로 넘겨 실행합니다.

   ```bash
   set -a; source .env; set +a
   ./gradlew :payment-service:bootRun
   ```

   - 로컬 포트는 `8083`입니다. `GET http://localhost:8083/actuator/health`로 상태를 확인합니다.
   - `PAYMENT_DB_URL`이 비어 있으면 `'url' must start with "jdbc"`로 시작을 멈춥니다. `.env`를 불러오지 않은 터미널에서 실행한 경우입니다.
   - Eureka 대신 원장·회원 서비스를 직접 주소로 부를 때는 `LEDGER_SERVICE_URL`·`USER_SERVICE_URL`을 지정합니다(예: `http://localhost:8085`). 비우면 Eureka에서 찾습니다. 지갑 서비스는 항상 Eureka로 찾습니다.
   - 배포 설정(`application-prod.yml`)은 `SPRING_CONFIG_NAME=application-prod`로 선택합니다.

5. 테이블은 앱이 시작할 때 Flyway가 만듭니다([DB 스키마](#db-스키마-flyway)).

Swagger UI(`http://localhost:8083/swagger-ui.html`, prod에서는 꺼짐)에서 결제 API는 두 그룹으로 나뉩니다.

- `payment`(결제, `/v3/api-docs/payment`): `/api/v1/payments/**` — 결제·환불, 가맹점, 정산
- `payment-admin`(결제 관리, `/v3/api-docs/payment-admin`): `/api/v1/admin/payments/**` — MASTER 전용 MANUAL_REVIEW 처리·처리 기록·오래된 환불 신청
- Gateway 없이 직접 호출할 때는 각 API의 `User-Id`·`User-Role` 헤더 입력칸을 채웁니다. 실제 환경에서는 Gateway가 세션 확인 후 붙입니다.
- 오류 코드는 Swagger에 적지 않습니다. API별 오류 코드는 이 문서의 각 API 설명을 봅니다.

## API 목록

| API | 설명 | 호출자 | CSRF 헤더 | `Idempotency-Key` | 성공 |
|---|---|---|---|---|---|
| `POST /api/v1/payments` | 결제 요청 | 회원 | 필요 | 필수 | 201 |
| `GET /api/v1/payments/{paymentId}` | 내 결제 상세 | 결제한 회원 | 불필요 | 없음 | 200 |
| `GET /api/v1/payments` | 내 결제 목록 | 회원 | 불필요 | 없음 | 200 |
| `POST /api/v1/payments/{paymentId}/refund-request` | 환불 신청 | 결제한 회원 | 필요 | 없음 | 200 |
| `DELETE /api/v1/payments/{paymentId}/refund-request` | 환불 신청 취소 | 결제한 회원 | 필요 | 없음 | 200 |
| `POST /api/v1/payments/{paymentId}/refund-request/reject` | 환불 신청 거절 | 가맹점 주인·`MASTER` | 필요 | 없음 | 200 |
| `POST /api/v1/payments/{paymentId}/refunds` | 환불 실행(가맹점 주인은 신청된 결제만) | 가맹점 주인·`MASTER` | 필요 | 필수 | 200 |
| `POST /api/v1/payments/merchants` | 가맹점 생성 | `MASTER` | 필요 | 없음 | 201·202 |
| `POST /api/v1/payments/merchants/{merchantId}/ledger-account` | 가맹점 원장 계정 생성 재시도 | `MASTER` | 필요 | 없음 | 200 |
| `GET /api/v1/payments/merchants/{merchantId}/balance` | 가맹점 잔액 | 가맹점 주인·`MASTER` | 불필요 | 없음 | 200 |
| `GET /api/v1/payments/merchants/{merchantId}/payments` | 가맹점 결제 목록 | 가맹점 주인·`MASTER` | 불필요 | 없음 | 200 |
| `GET /api/v1/payments/merchants/{merchantId}/payments/{paymentId}` | 가맹점 결제 상세 | 가맹점 주인·`MASTER` | 불필요 | 없음 | 200 |
| `GET /api/v1/payments/merchants/{merchantId}/settlements/preview` | 정산 미리보기 | 가맹점 주인·`MASTER` | 불필요 | 없음 | 200 |
| `POST /api/v1/payments/merchants/{merchantId}/settlements` | 정산 요청 | 가맹점 주인·`MASTER` | 필요 | 필수 | 201 |
| `GET /api/v1/payments/merchants/{merchantId}/settlements` | 정산 목록 | 가맹점 주인·`MASTER` | 불필요 | 없음 | 200 |
| `GET /api/v1/payments/merchants/{merchantId}/settlements/{settlementId}` | 정산 상세(포함된 결제) | 가맹점 주인·`MASTER` | 불필요 | 없음 | 200 |
| `GET /api/v1/admin/payments/manual-reviews` | `MANUAL_REVIEW` 결제 목록 | `MASTER` | 불필요 | 없음 | 200 |
| `GET /api/v1/admin/payments/{paymentId}` | `MANUAL_REVIEW` 결제 단건(원장 조회·예상 결과) | `MASTER` | 불필요 | 없음 | 200 |
| `POST /api/v1/admin/payments/{paymentId}/resolve` | `MANUAL_REVIEW` 결제 처리 | `MASTER` | 필요 | 없음 | 200 |
| `GET /api/v1/admin/payments/refunds/manual-reviews` | `MANUAL_REVIEW` 환불 목록 | `MASTER` | 불필요 | 없음 | 200 |
| `GET /api/v1/admin/payments/refunds/{refundId}` | `MANUAL_REVIEW` 환불 단건(원장 조회·예상 결과) | `MASTER` | 불필요 | 없음 | 200 |
| `POST /api/v1/admin/payments/refunds/{refundId}/resolve` | `MANUAL_REVIEW` 환불 처리 | `MASTER` | 필요 | 없음 | 200 |
| `GET /api/v1/admin/payments/resolutions` | 운영자 처리 기록 목록 | `MASTER` | 불필요 | 없음 | 200 |
| `GET /api/v1/admin/payments/refund-requests/overdue` | 3일 넘게 처리되지 않은 환불 신청 | `MASTER` | 불필요 | 없음 | 200 |
| `GET /api/v1/admin/payments/settlements/manual-reviews` | `MANUAL_REVIEW` 정산 목록 | `MASTER` | 불필요 | 없음 | 200 |
| `GET /api/v1/admin/payments/settlements/{settlementId}` | `MANUAL_REVIEW` 정산 단건(원장 조회·예상 결과) | `MASTER` | 불필요 | 없음 | 200 |
| `POST /api/v1/admin/payments/settlements/{settlementId}/resolve` | `MANUAL_REVIEW` 정산 처리 | `MASTER` | 필요 | 없음 | 200 |
| `GET /api/v1/admin/payments/settlements/resolutions` | 정산 운영자 처리 기록 목록 | `MASTER` | 불필요 | 없음 | 200 |

모든 API는 로그인이 필요합니다. 응답 본문은 `success`·`data`·`error` 공통 형식입니다. ID는 모두 UUID이고, 금액은 통화별 최소 단위 정수(KRW 원, JPY 엔, CNY 펀)이며, 시각은 UTC ISO-8601입니다.

로그인·CSRF는 Gateway가 확인하고, payment-service는 Gateway가 넣어 준 `User-Id`·`User-Role` 헤더로 요청자를 정합니다. 헤더가 없거나 일부만 있거나 형식이 잘못되면 `401 COMMON_UNAUTHORIZED`입니다.

권한이 없는 경우의 응답은 API마다 다릅니다.

| 상황 | 결과 |
|---|---|
| `MASTER`가 아닌 회원이 가맹점 생성·원장 계정 재시도 | 403 `COMMON_FORBIDDEN` |
| `MASTER`가 아닌 회원이 `/api/v1/admin/payments/**` | 403 `COMMON_FORBIDDEN` |
| 가맹점 주인·`MASTER`가 아닌 회원이 가맹점 잔액·결제 조회, 정산 미리보기·요청·조회 | 404 `PAYMENT_MERCHANT_NOT_FOUND` (가맹점이 있는지 알리지 않음) |
| 가맹점 주인·`MASTER`가 아닌 회원이 환불 | 404 `PAYMENT_NOT_FOUND` (결제한 회원 본인도 환불할 수 없음) |
| 가맹점 주인·`MASTER`가 아닌 회원이 환불 신청 거절 | 404 `PAYMENT_NOT_FOUND` |
| 결제한 회원이 아닌 사람이 환불 신청·취소 | 404 `PAYMENT_NOT_FOUND` (가맹점 주인도 고객 대신 신청할 수 없음) |
| 결제한 회원이 아닌 사람이 `GET /api/v1/payments/{paymentId}` | 404 `PAYMENT_NOT_FOUND` |

## 호출 순서

로그인과 CSRF 토큰 받기는 [User 가이드](user-service.md#호출-순서)와 같습니다.

```txt
[관리자]
1. POST /api/v1/payments/merchants                         → 가맹점 생성, 주인 회원 지정
   - 201 ACTIVE: 바로 결제 가능
   - 202 PENDING: 원장 계정 생성 실패. 2번으로 다시 시도
2. POST /api/v1/payments/merchants/{merchantId}/ledger-account → PENDING 가맹점의 원장 계정 다시 만들기

[결제자]
3. POST /api/v1/payments                                   → Csrf-Token·Idempotency-Key 헤더 포함
   - 201 APPROVED: 결제 완료
   - 201 PENDING: 결과 확인 중. 서버가 자동으로 다시 처리하므로 4번으로 상태 확인
   - 422 PAYMENT_DECLINED: 잔액 부족 등으로 거절(결제는 DECLINED로 기록)
4. GET  /api/v1/payments/{paymentId}                       → 최종 상태 확인
5. GET  /api/v1/payments                                   → 내 결제 목록
6. POST   /api/v1/payments/{paymentId}/refund-request      → 환불 신청(사유 필수). 결제 승인 후 3일 안
7. DELETE /api/v1/payments/{paymentId}/refund-request      → 가맹점이 처리하기 전이면 신청 취소

[가맹점 주인]
8.  GET  /api/v1/payments/merchants/{merchantId}/payments  → 받은 결제 목록(환불된 금액 포함)
    - ?status=REFUND_REQUESTED: 처리할 환불 신청만
9.  POST /api/v1/payments/{paymentId}/refunds              → 신청된 결제를 부분·전액 환불(금액은 가맹점이 정함)
    POST /api/v1/payments/{paymentId}/refund-request/reject → 또는 신청 거절(사유 필수)
10. GET  /api/v1/payments/merchants/{merchantId}/balance   → 가맹점 잔액

[가맹점 주인 - 정산 (MASTER도 대신 요청 가능, 돈은 항상 주인 지갑으로)]
11. GET  /api/v1/payments/merchants/{merchantId}/settlements/preview → 지금 정산될 금액, 환불 기간을 기다리는 금액
12. POST /api/v1/payments/merchants/{merchantId}/settlements         → Csrf-Token·Idempotency-Key 헤더 포함
    - 201 PAID: 주인 지갑으로 입금 완료
    - 201 REQUESTED: 결과 확인 중. 서버가 자동으로 다시 처리하므로 13·14번으로 확인
    - 201 MANUAL_REVIEW: 원장이 요청을 받아들이지 않아 자동 처리를 멈춤. 관리자가 처리(20~22번)
    - 422 PAYMENT_NO_SETTLEMENT_TARGET: 정산할 결제 없음
13. GET  /api/v1/payments/merchants/{merchantId}/settlements         → 정산 목록
14. GET  /api/v1/payments/merchants/{merchantId}/settlements/{settlementId} → 정산 상세(포함된 결제)

[관리자 - 멈춘 결제·환불 처리]
15. GET  /api/v1/admin/payments/manual-reviews             → MANUAL_REVIEW 결제 목록 (환불은 /refunds/manual-reviews)
16. GET  /api/v1/admin/payments/{paymentId}                → 원장 조회 결과와 예상 처리 결과 확인
17. POST /api/v1/admin/payments/{paymentId}/resolve        → 처리(memo 선택). 결과는 원장 조회로 서버가 정함
18. GET  /api/v1/admin/payments/resolutions                → 처리 기록 목록(누가 언제 어떻게 처리했는지)

[관리자 - 오래된 환불 신청]
19. GET  /api/v1/admin/payments/refund-requests/overdue    → 3일 넘게 가맹점이 처리하지 않은 환불 신청

[관리자 - 멈춘 정산 처리]
20. GET  /api/v1/admin/payments/settlements/manual-reviews → MANUAL_REVIEW 정산 목록
21. GET  /api/v1/admin/payments/settlements/{settlementId}  → 원장 조회 결과와 예상 처리 결과 확인
22. POST /api/v1/admin/payments/settlements/{settlementId}/resolve → 처리(memo 선택). PAID 또는 FAILED
```

결제·환불·정산 응답을 받지 못해 다시 보낼 때는 **같은 `Idempotency-Key`와 같은 본문**을 사용합니다. 그래야 두 번 처리되지 않습니다. 새 결제·환불마다 새 키를 만듭니다(UUID 권장).

curl로 Gateway 없이 확인할 때는 회원 헤더를 직접 넣습니다. 아래 ID는 예시입니다.

```bash
BASE=http://localhost:8083
MASTER=11111111-1111-1111-1111-111111111111
OWNER=22222222-2222-2222-2222-222222222222
PAYER=33333333-3333-3333-3333-333333333333

# 가맹점 생성 (MASTER)
curl -s -H "User-Id: $MASTER" -H 'User-Role: MASTER' -H 'Content-Type: application/json' \
  -d "{\"name\":\"Cafe\",\"currency\":\"KRW\",\"ownerUserId\":\"$OWNER\"}" \
  "$BASE/api/v1/payments/merchants"

MERCHANT=<응답의 merchantId>

# 결제 (결제자)
curl -s -H "User-Id: $PAYER" -H 'User-Role: USER' \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d "{\"merchantId\":\"$MERCHANT\",\"currency\":\"KRW\",\"amount\":5000,\"paymentInfo\":\"coffee\"}" \
  "$BASE/api/v1/payments"

PAYMENT=<응답의 paymentId>

# 환불 신청 (결제자)
curl -s -X POST -H "User-Id: $PAYER" -H 'User-Role: USER' -H 'Content-Type: application/json' \
  -d '{"reason":"size change"}' "$BASE/api/v1/payments/$PAYMENT/refund-request"

# 부분 환불 (가맹점 주인, 신청된 결제만)
curl -s -H "User-Id: $OWNER" -H 'User-Role: USER' \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"amount":2000,"reason":"size change"}' \
  "$BASE/api/v1/payments/$PAYMENT/refunds"

# 정산 미리보기·요청·목록 (가맹점 주인)
curl -s -H "User-Id: $OWNER" -H 'User-Role: USER' "$BASE/api/v1/payments/merchants/$MERCHANT/settlements/preview"
curl -s -X POST -H "User-Id: $OWNER" -H 'User-Role: USER' -H "Idempotency-Key: $(uuidgen)" \
  "$BASE/api/v1/payments/merchants/$MERCHANT/settlements"
curl -s -H "User-Id: $OWNER" -H 'User-Role: USER' "$BASE/api/v1/payments/merchants/$MERCHANT/settlements"

# MANUAL_REVIEW 결제 확인 후 처리 (MASTER)
curl -s -H "User-Id: $MASTER" -H 'User-Role: MASTER' "$BASE/api/v1/admin/payments/manual-reviews"
curl -s -H "User-Id: $MASTER" -H 'User-Role: MASTER' "$BASE/api/v1/admin/payments/$PAYMENT"
curl -s -X POST -H "User-Id: $MASTER" -H 'User-Role: MASTER' -H 'Content-Type: application/json' \
  -d '{"memo":"ledger recovered"}' "$BASE/api/v1/admin/payments/$PAYMENT/resolve"

# 처리 기록 목록 (MASTER) - 특정 결제, 특정 운영자
curl -s -H "User-Id: $MASTER" -H 'User-Role: MASTER' "$BASE/api/v1/admin/payments/resolutions?paymentId=$PAYMENT"
curl -s -H "User-Id: $MASTER" -H 'User-Role: MASTER' "$BASE/api/v1/admin/payments/resolutions?actorId=$MASTER&type=REFUND"
```

Git Bash에서 한글이 든 본문을 인자로 넘기면 글자가 깨져 400이 날 수 있습니다. 이때는 UTF-8 파일로 저장해 `--data-binary @body.json`으로 보냅니다.

## 가맹점

### 가맹점 생성

| 항목 | 규칙 |
|---|---|
| `name` | 필수, 255자 이하 |
| `currency` | 필수, `KRW`·`JPY`·`CNY`. 가맹점은 통화 하나만 받습니다 |
| `ownerUserId` | 필수, UUID. `ACTIVE` 회원이어야 합니다. 한 회원이 여러 가맹점의 주인이 될 수 있습니다 |

```json
{
  "name": "Cafe",
  "currency": "KRW",
  "ownerUserId": "22222222-2222-2222-2222-222222222222"
}
```

처리 순서:

1. 주인 회원을 user-service I02로 확인합니다. 활성 회원이 아니면 404 `PAYMENT_MERCHANT_OWNER_NOT_FOUND`.
2. 가맹점을 `PENDING`으로 저장(커밋)합니다.
3. 원장에 가맹점의 지갑 계정을 만듭니다(`POST /internal/v1/ledger/accounts`). 원장 지갑 ID로 **가맹점 ID를 그대로** 씁니다.
4. 계정이 생기면 `ledger_wallet_id`를 채우고 `ACTIVE`로 바꿔 201로 응답합니다. 원장 호출이 실패하면 `PENDING`으로 남기고 202로 응답합니다.

`PENDING` 가맹점은 결제를 받지 않습니다. `ledger-account` 재시도 API로 원장 계정을 다시 만들면 `ACTIVE`가 됩니다. 이미 `PENDING`이 아니면 원장을 부르지 않고 현재 상태로 200을 응답합니다. 재시도 중 원장 호출이 실패하면 [다른 서비스 호출](#다른-서비스-호출)의 공통 오류(502·503·504)로 응답합니다.

```json
{
  "success": true,
  "data": {
    "merchantId": "9b2f3c1e-8a47-4d55-b0c2-7e1f6a9d3b10",
    "name": "Cafe",
    "currency": "KRW",
    "ownerUserId": "22222222-2222-2222-2222-222222222222",
    "status": "ACTIVE",
    "createdAt": "2026-11-04T01:02:03.123456Z"
  },
  "error": null
}
```

### 가맹점 상태

| 상태 | 의미 |
|---|---|
| `PENDING` | 원장 계정이 아직 없음. 결제 불가, 잔액 조회 불가 |
| `ACTIVE` | 결제 가능 |
| `SUSPENDED` | 결제 불가, 정산은 가능. 상태를 바꾸는 API는 아직 없어 DB에서만 바꿉니다 |

### 가맹점 잔액

원장에서 가맹점 지갑의 가맹점 통화 계정 잔액을 읽어 응답합니다. `PENDING` 가맹점은 422 `PAYMENT_MERCHANT_NOT_ACTIVE`입니다. `SUSPENDED` 가맹점은 조회할 수 있습니다.

```json
{
  "success": true,
  "data": {
    "merchantId": "9b2f3c1e-8a47-4d55-b0c2-7e1f6a9d3b10",
    "currency": "KRW",
    "balance": 3000
  },
  "error": null
}
```

## 결제 요청

### 요청

| 항목 | 규칙 |
|---|---|
| `Idempotency-Key` 헤더 | 필수, 공백이 아닌 255자 이하. 없거나 형식이 틀리면 400 `COMMON_INVALID_REQUEST` |
| `merchantId` | 필수, UUID |
| `currency` | 필수, `KRW`·`JPY`·`CNY`. 가맹점 통화와 다르면 400 `PAYMENT_CURRENCY_MISMATCH` |
| `amount` | 필수, 0보다 큰 정수(통화별 최소 단위) |
| `paymentInfo` | 선택, 255자 이하. 주문 내용 등 결제 설명 |

```json
{
  "merchantId": "9b2f3c1e-8a47-4d55-b0c2-7e1f6a9d3b10",
  "currency": "KRW",
  "amount": 5000,
  "paymentInfo": "coffee"
}
```

### 처리 순서

1. 같은 회원·같은 키의 결제가 이미 있으면 아래 [멱등성](#결제-멱등성) 규칙으로 응답합니다.
2. 가맹점이 있는지 확인합니다. 없으면 404 `PAYMENT_MERCHANT_NOT_FOUND`. `ACTIVE`가 아니거나 원장 계정이 없으면 422 `PAYMENT_MERCHANT_NOT_ACTIVE`.
3. 요청 통화가 가맹점 통화와 같은지 확인합니다.
4. 결제자 지갑을 조회합니다. 없으면 404 `PAYMENT_WALLET_NOT_FOUND`, `ACTIVE`가 아니면 422 `PAYMENT_WALLET_NOT_ACTIVE`.
5. 결제를 `PENDING`으로 저장하고 생성 이력을 남깁니다(커밋). 원장 호출 중에는 DB 트랜잭션을 잡지 않습니다.
6. 원장에 거래를 등록하고 결과로 상태를 정합니다([원장 연동](#원장-연동과-복구)).

가맹점명은 결제할 때의 이름을 결제에 복사해 둡니다. 가맹점·지갑 확인은 빠른 거절용이며 최종 판단(잔액, 계정 상태)은 원장 거래 등록 결과입니다.

### 응답

결제 요청은 결과와 관계없이 성공하면 **201**입니다. 클라이언트는 `status`로 결과를 구분합니다.

| 상태 | HTTP | 의미 |
|---|---|---|
| `APPROVED` | 201 | 원장 반영 완료, 결제자 차감·가맹점 입금 끝 |
| `PENDING` | 201 | 원장 결과를 확인하지 못함. 서버가 자동으로 다시 처리 |
| `MANUAL_REVIEW` | 201 | 자동 처리를 멈춤. 운영자 확인 필요 |
| `DECLINED` | 422 | 원장이 거절. 오류 응답으로 반환되며 결제는 기록에 남음 |

```json
{
  "success": true,
  "data": {
    "paymentId": "c7d4e2a9-5b13-4f8e-9a61-0d2b7c8e4f35",
    "status": "APPROVED",
    "amount": 5000,
    "currency": "KRW",
    "merchantName": "Cafe",
    "paymentInfo": "coffee",
    "declineReason": null,
    "refunds": [],
    "createdAt": "2026-11-04T01:02:03.123456Z",
    "updatedAt": "2026-11-04T01:02:03.456789Z"
  },
  "error": null
}
```

거절(`DECLINED`)되면 거절 사유를 `error.details`에 담습니다. 원장의 오류 코드는 그대로 내보내지 않고 결제 쪽 문구로 바꿉니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "PAYMENT_DECLINED",
    "message": "결제가 거절되었습니다.",
    "details": [{ "field": "declineReason", "message": "잔액이 부족합니다." }]
  }
}
```

### 결제 멱등성

| 상황 | 결과 |
|---|---|
| 같은 키·같은 요청, 결제 `APPROVED`·`PENDING`·`MANUAL_REVIEW`·환불 관련 상태 | 201, 현재 결제 |
| 같은 키·같은 요청, 결제 `DECLINED` | 처음과 같은 422 |
| 같은 키·다른 요청 | 409 `PAYMENT_IDEMPOTENCY_KEY_REUSED` |
| 같은 키 동시 요청 | 하나만 저장되고 나머지는 위 규칙으로 응답 |

- 같은 요청인지는 `merchantId`·`currency`·`amount`로 비교합니다. `paymentInfo`는 비교하지 않습니다.
- 재요청은 원장을 다시 호출하지 않고 가맹점·지갑도 다시 조회하지 않습니다. 결과 모름 결제의 재전송은 [복구 스케줄러](#복구-스케줄러)만 합니다.
- 중복 방지는 DB `UNIQUE(user_id, idempotency_key)`로 보장합니다. 키는 결제자별로 구분합니다.

## 결제 조회

### 내 결제

`GET /api/v1/payments/{paymentId}`는 결제한 회원만 조회할 수 있습니다. 응답은 결제 요청 응답과 같은 결제 객체이며 `refunds`에 이 결제의 환불 내역이 모두 들어 있습니다. 상세 조회에는 가장 최근 고객 환불 신청의 요약(`refundRequest`)도 들어 있습니다.

| `refundRequest` 필드 | 내용 |
|---|---|
| `result` | `REQUESTED`(가맹점 처리 대기)·`CANCELLED`(고객 취소)·`REJECTED`(가맹점 주인·`MASTER` 거절)·`ACCEPTED`(가맹점이 환불 실행) |
| `requestedAt`·`requestReason` | 신청 시각과 고객이 적은 사유 |
| `processedAt` | 취소·거절·실행 시각. `REQUESTED`면 `null` |
| `rejectReason` | 거절 사유. `REJECTED`일 때만 값이 있음 |

- 신청한 적이 없으면 `refundRequest`는 `null`입니다. 결제 목록과 결제 생성·환불 실행 응답에서는 이력을 조회하지 않아 항상 `null`입니다(신청·취소·거절 응답에는 들어 있음).
- `ACCEPTED`는 가맹점이 환불을 실행했다는 뜻입니다. 원장이 그 환불을 거절해도 `ACCEPTED`로 남으므로 환불 결과는 `refunds`에서 확인합니다.
- 요약은 `payment_histories`에서 가장 최근의 `→ REFUND_REQUESTED` 기록과 그다음 기록으로 만듭니다.

`GET /api/v1/payments`는 내 결제 목록입니다.

| 파라미터 | 규칙 |
|---|---|
| `status` | 선택. 결제 상태 값으로 거르기. 그 밖의 값은 400 |
| `page` | 0부터, 기본 0. 0 미만은 400 |
| `size` | 기본 20, 1 미만은 400, 100을 넘으면 100으로 조회 |

정렬은 `createdAt desc, id desc`로 고정입니다. 응답은 common `PageResponse`(`items`·`page`·`size`·`totalElements`·`totalPages`)이고, 목록 항목은 `paymentId`·`status`·`amount`·`currency`·`merchantName`·`createdAt`만 담습니다.

### 가맹점 결제

가맹점 주인과 `MASTER`만 조회할 수 있습니다. 파라미터와 정렬은 내 결제 목록과 같습니다.

목록 항목은 `paymentId`·`status`·`amount`·`refundedAmount`·`currency`·`paymentInfo`·`createdAt`입니다. `refundedAmount`는 그 결제의 **완료된** 환불 금액 합계이며, 진행 중이거나 거절된 환불은 넣지 않습니다.

상세(`.../payments/{paymentId}`)는 결제 객체를 응답하며, 내 결제 상세와 같이 `refundRequest`가 들어 있습니다. 다른 가맹점의 결제면 404 `PAYMENT_NOT_FOUND`입니다. 처리할 환불 신청은 목록을 `status=REFUND_REQUESTED`로 걸러서 봅니다.

## 환불

고객이 환불을 신청하고 가맹점이 실행합니다. 가맹점 주인은 고객이 신청한 결제(`REFUND_REQUESTED`)만 환불할 수 있고, 금액은 가맹점이 정합니다. `MASTER`는 신청이 없는 승인 결제도 바로 환불할 수 있습니다(운영 예외 통로). 결제 한 건을 여러 번 나눠 환불할 수 있으며, 부분 환불 뒤 남은 금액은 고객이 다시 신청합니다.

### 고객 환불 신청

| API | 상태 변화 | 본문 | 이력 처리자 |
|---|---|---|---|
| 신청 `POST .../refund-request` | `APPROVED` → `REFUND_REQUESTED` | `{"reason"}` 필수, 공백이 아닌 255자 이하. 본문이 없으면 400 | `USER` |
| 취소 `DELETE .../refund-request` | `REFUND_REQUESTED` → `APPROVED` | 없음 | `USER` |
| 거절 `POST .../refund-request/reject` | `REFUND_REQUESTED` → `APPROVED` | `{"reason"}` 필수, 공백이 아닌 255자 이하 | `MERCHANT`(가맹점 주인)·`MASTER` |

- 세 API 모두 원장을 호출하지 않고 한 트랜잭션에서 처리합니다. `Idempotency-Key`는 받지 않습니다. 응답은 200과 결제 객체(`refundRequest` 포함)입니다.
- 신청 사유와 거절 사유는 `payment_histories.reason`에 저장합니다. 거절 사유는 고객에게 `refundRequest.rejectReason`으로 보입니다.
- **환불 기간(3일)은 신청할 때만** 확인합니다. 결제 승인 시각에서 3일이 지났으면 422 `PAYMENT_REFUND_PERIOD_EXPIRED`입니다. 승인 시각은 `payment_histories`의 `PENDING → APPROVED` 기록이고, 운영자 처리로 승인된 결제는 `MANUAL_REVIEW → APPROVED` 기록(처리 시각)입니다. 부분 환불·거절·취소 뒤의 `→ APPROVED`는 승인 시각이 아닙니다.
- 기간 안에 들어온 신청은 가맹점 처리가 늦어도 실행할 수 있습니다. 거절·취소된 뒤에도 승인 후 3일 안이면 다시 신청할 수 있습니다.

| 상황 | 결과 |
|---|---|
| 이미 신청한 결제에 다시 신청 | 200, 현재 상태(처음 신청 사유 유지) |
| 결제가 `REFUND_PENDING`(가맹점이 이미 실행) | 신청·취소·거절 모두 409 `PAYMENT_REFUND_IN_PROGRESS` |
| 결제가 `REFUNDED` | 신청 409 `PAYMENT_ALREADY_REFUNDED` |
| 결제가 `PENDING`·`DECLINED`·`MANUAL_REVIEW` | 신청 409 `PAYMENT_NOT_REFUNDABLE` |
| 신청 중이 아닌 결제에 취소·거절 | 422 `PAYMENT_REFUND_NOT_REQUESTED` |

- 동시 요청: 결제의 `@Version`으로 먼저 저장된 쪽만 반영됩니다. 고객 취소와 가맹점 실행이 함께 들어오면 늦은 쪽은 409 `COMMON_CONFLICT`이거나, 바뀐 상태를 보고 422 `PAYMENT_REFUND_NOT_REQUESTED`·409 `PAYMENT_REFUND_IN_PROGRESS`입니다. 같은 고객의 신청이 동시에 들어오면 둘 다 200입니다.
- 가맹점이 응답하지 않아도 자동 환불·자동 만료는 없습니다. 고객은 신청을 취소할 수 있고, `MASTER`가 직접 환불하거나 거절할 수 있습니다.
- 신청 후 3일 넘게 처리되지 않은 신청은 `GET /api/v1/admin/payments/refund-requests/overdue`(오래된 순, `size` 최대 100)와 지표 `payment.refund_request.overdue`로 확인합니다. 항목은 `paymentId`·`userId`·`merchantId`·`amount`·`currency`·`requestedAt`·`requestReason`입니다. 신청 중에는 결제의 `updated_at`이 신청 시각입니다.
- [정산](#정산)은 `APPROVED` 결제만 대상이라 신청 중인 결제는 자연히 빠집니다. 가맹점이 거절해 `APPROVED`로 돌아오면 다음 정산 요청 때 대상이 됩니다.

### 환불 실행 요청

| 항목 | 규칙 |
|---|---|
| `Idempotency-Key` 헤더 | 필수, 공백이 아닌 255자 이하. 없거나 형식이 틀리면 400 `COMMON_INVALID_REQUEST` |
| `amount` | 필수, 0보다 큰 정수. 남은 금액 이하 |
| `reason` | 필수, 공백이 아닌 255자 이하 |

```json
{
  "amount": 2000,
  "reason": "size change"
}
```

### 처리 순서

1. 결제를 찾고, 요청자가 결제한 가맹점의 주인이거나 `MASTER`인지 확인합니다. 아니면 404 `PAYMENT_NOT_FOUND`.
2. 같은 요청자·같은 키의 환불이 이미 있으면 아래 [멱등성](#환불-멱등성) 규칙으로 응답합니다.
3. 결제 상태를 확인합니다([환불할 수 없는 경우](#환불할-수-없는-경우)). 가맹점 주인은 `REFUND_REQUESTED`에서만, `MASTER`는 `APPROVED`·`REFUND_REQUESTED`에서 실행할 수 있습니다.
4. 환불 기간은 여기서 확인하지 않습니다. 기간은 [고객 신청](#고객-환불-신청) 때 확인하며, `MASTER` 직접 환불은 기간 제한이 없습니다. 다만 [정산](#정산)에 들어간 결제(정산 `REQUESTED`·`PAID`·`MANUAL_REVIEW`)는 `MASTER`도 환불할 수 없습니다(409 `PAYMENT_ALREADY_SETTLED`).
5. 한 트랜잭션에서 결제를 행 잠금(`SELECT ... FOR UPDATE`)으로 다시 읽어 상태와 정산 여부를 확인하고, 남은 금액을 다시 계산하고, 환불을 `REQUESTED`로 저장하고, 결제를 `REFUND_PENDING`으로 바꿉니다(커밋).
   - 남은 금액 = 결제 금액 − (`COMPLETED` + `REQUESTED` + `MANUAL_REVIEW` 환불 합계)
   - 요청 금액이 남은 금액보다 크면 422 `PAYMENT_REFUND_AMOUNT_EXCEEDED`. 트랜잭션이 롤백되어 결제는 `REFUND_REQUESTED`에 남습니다.
6. 원장에 환불 거래를 등록하고 결과로 상태를 정합니다.

| 원장 결과 | 환불 | 결제 |
|---|---|---|
| 반영, 남은 금액 0 | `COMPLETED` | `REFUNDED` |
| 반영, 남은 금액 있음(부분 환불) | `COMPLETED` | `APPROVED` (남은 금액은 고객이 다시 신청) |
| 거절 | `DECLINED` | `APPROVED` (결제는 그대로 유효) |
| 결과 모름 | `REQUESTED` 유지 | `REFUND_PENDING` 유지 |
| 받아들이지 않음·결과 모름 5회 | `MANUAL_REVIEW` | `REFUND_PENDING` 유지 |

### 응답

성공하면 200이고 `data`는 **결제 객체**입니다. 방금 만든 환불은 `refunds` 목록에 들어 있습니다. 결과를 모르는 동안 결제는 `REFUND_PENDING`, 환불은 `REQUESTED`로 보입니다.

```json
{
  "success": true,
  "data": {
    "paymentId": "c7d4e2a9-5b13-4f8e-9a61-0d2b7c8e4f35",
    "status": "APPROVED",
    "amount": 5000,
    "currency": "KRW",
    "merchantName": "Cafe",
    "paymentInfo": "coffee",
    "declineReason": null,
    "refunds": [
      {
        "refundId": "5e8a1c3d-2f47-4b9a-8d61-3c0e7b2a9f14",
        "status": "COMPLETED",
        "amount": 2000,
        "currency": "KRW",
        "reason": "size change",
        "declineReason": null,
        "createdAt": "2026-11-04T02:00:00.000000Z",
        "updatedAt": "2026-11-04T02:00:00.300000Z"
      }
    ],
    "createdAt": "2026-11-04T01:02:03.123456Z",
    "updatedAt": "2026-11-04T02:00:00.300000Z"
  },
  "error": null
}
```

원장이 거절하면 422 `PAYMENT_REFUND_DECLINED`이고 거절 사유를 `error.details`의 `declineReason`에 담습니다. 가맹점 잔액 부족으로 거절된 경우도 따로 알리지 않고 "환불이 거절되었습니다."로 응답합니다.

### 환불할 수 없는 경우

| 결제 상태 | 결과 |
|---|---|
| `REFUND_PENDING` (다른 환불 처리 중) | 409 `PAYMENT_REFUND_IN_PROGRESS` |
| `REFUNDED` | 409 `PAYMENT_ALREADY_REFUNDED` |
| `PENDING`·`DECLINED`·`MANUAL_REVIEW` | 409 `PAYMENT_NOT_REFUNDABLE` |
| `APPROVED`인데 가맹점 주인이 실행(고객 신청 없음) | 422 `PAYMENT_REFUND_NOT_REQUESTED` |
| 정산에 들어간 결제(정산 `REQUESTED`·`PAID`·`MANUAL_REVIEW`, `MASTER` 직접 환불 포함) | 409 `PAYMENT_ALREADY_SETTLED` |

환불은 결제 한 건에 한 번에 하나씩만 진행됩니다. 같은 결제에 동시에 환불을 보내면 하나만 저장되고, 나머지는 409(`PAYMENT_REFUND_IN_PROGRESS` 또는 `COMMON_CONFLICT`)입니다. 결제의 `@Version` 낙관적 락으로 막습니다.

### 환불 멱등성

| 상황 | 결과 |
|---|---|
| 같은 키·같은 요청, 환불 `DECLINED` | 처음과 같은 422 |
| 같은 키·같은 요청, 그 밖의 상태 | 200, 현재 결제 객체 |
| 같은 키·다른 요청 | 409 `PAYMENT_REFUND_IDEMPOTENCY_KEY_REUSED` |

- 같은 요청인지는 `paymentId`·`amount`로 비교합니다. `reason`은 비교하지 않습니다.
- 키는 요청자(가맹점 주인·`MASTER`)별로 구분하며, 다른 결제에 같은 키를 쓰면 다른 요청으로 봅니다(`UNIQUE(user_id, idempotency_key)`).
- 재요청은 원장을 다시 호출하지 않습니다. 기간·상태 확인보다 먼저 처리하므로, 처리가 끝난 환불을 다시 보내도 409·422가 아니라 처음 결과가 나옵니다.

## 정산

가맹점 원장 지갑에 쌓인 결제 대금을 가맹점 주인의 회원 지갑으로 보냅니다. 자동 배치는 없고 **가맹점 주인이나 `MASTER`가 요청할 때** 그 시점의 대상 결제를 모아 한 번에 정산합니다. `MASTER`가 요청해도 돈은 항상 가맹점 주인 지갑으로 갑니다. 이번 범위는 원장 기록(지갑 간 이동)까지이며 은행 계좌로의 실제 이체는 없습니다.

### 정산 대상

| 조건 | 내용 |
|---|---|
| 결제 상태 | `APPROVED`만. `REFUND_REQUESTED`·`REFUND_PENDING`·`REFUNDED` 등은 제외 |
| 기간 | 승인 시각 + 환불 기간(3일) + 여유 시간(1시간)이 요청 시각보다 이른 결제 |
| 미정산 | 아직 어느 정산에도 들어가지 않은 결제(`settlement_items`에 없음) |
| 금액 | 결제 금액 − **완료된** 환불 합계. 부분 환불 뒤 남은 금액을 정산하고, 남은 금액이 0인 결제는 뺍니다 |
| 건수 | 정산 한 번에 결제 **최대 1,000건**(결제 생성 순). 나머지는 다음 정산 요청에서 정산합니다 |

- 승인 시각은 [환불 기간](#고객-환불-신청)과 같은 기준(`PENDING`·`MANUAL_REVIEW` → `APPROVED` 이력)입니다.
- 여유 시간은 환불 기간이 끝나는 순간에 들어온 환불 신청과 정산이 겹치지 않게 둡니다.
- 환불 기간·여유 시간은 설정 파일이 아니라 상수(`PaymentService.REFUND_PERIOD`, `SettlementService.CUTOFF_MARGIN`)입니다. 시연에서 짧게 쓰려면 상수를 바꾸고 재시작합니다.
- 수수료는 없습니다. 가맹점은 통화가 하나라 결제·환불·정산이 모두 같은 통화이고 환전하지 않습니다.
- 정지(`SUSPENDED`) 가맹점도 정산합니다. 원장 계정이 없는(`PENDING`) 가맹점은 422 `PAYMENT_MERCHANT_NOT_ACTIVE`입니다.

### 정산 미리보기

`GET .../settlements/preview`는 정산을 만들지 않고 지금 상태를 보여 줍니다.

```json
{
  "success": true,
  "data": {
    "currency": "KRW",
    "available": { "count": 1, "amount": 10000 },
    "nextSettlement": { "count": 1, "amount": 10000 },
    "waiting": { "count": 2, "amount": 8000 },
    "nextAvailableAt": "2026-11-12T11:10:00Z"
  },
  "error": null
}
```

| 필드 | 내용 |
|---|---|
| `available` | 지금 정산할 수 있는 결제 전체 건수·금액(위 [정산 대상](#정산-대상)과 같은 기준) |
| `nextSettlement` | `available` 중 다음 정산 요청 한 번에 들어갈 몫(결제 생성 순 앞의 최대 1,000건). `available`보다 작으면 나머지는 그다음 요청에서 정산 |
| `waiting` | `APPROVED`·미정산이지만 환불 기간 + 여유 시간이 아직 지나지 않은 결제 건수·금액 |
| `nextAvailableAt` | `waiting` 중 가장 먼저 정산 가능해지는 시각. 없으면 `null` |

- 조회 시점 기준이라 실제 요청 때는 그 사이의 환불·기간 경과로 금액이 달라질 수 있습니다.
- 정산 요청과 달리 결제 행을 잠그지 않으므로 미리보기 조회가 환불을 막지 않습니다. 건수 상한이 없어 환불 조회는 1,000건씩 나눠 합니다.

### 정산 요청 처리 순서

1. 가맹점을 찾고 요청자가 주인이거나 `MASTER`인지 확인합니다. 아니면 404 `PAYMENT_MERCHANT_NOT_FOUND`.
2. 같은 요청자·같은 키의 정산이 있으면 아래 [멱등성](#정산-멱등성) 규칙으로 응답합니다.
3. 가맹점에 원장 계정이 있는지 확인합니다.
4. 가맹점 주인의 지갑을 wallet-service에서 조회합니다. 없거나 `ACTIVE`가 아니면 422 `PAYMENT_SETTLEMENT_WALLET_NOT_AVAILABLE`.
5. 한 트랜잭션에서 대상 결제를 결제 생성 순으로 최대 1,000건 **행 잠금**(`FOR UPDATE`)으로 읽고, 항목 금액과 합계를 계산해 정산(`REQUESTED`)과 정산 항목을 저장합니다(커밋). 대상이 없으면 422 `PAYMENT_NO_SETTLEMENT_TARGET`.
6. 원장에 정산 거래를 등록하고 결과로 상태를 정합니다([원장 거래 등록](#원장-거래-등록)).

| 원장 결과 | 정산 | 응답 |
|---|---|---|
| 반영 | `PAID`, 원장 거래 ID 저장 | 201 |
| 거절 | `FAILED`, 거절 사유 저장, **정산 항목 삭제** | 422 `PAYMENT_SETTLEMENT_DECLINED` (`details`에 사유) |
| 결과 모름 1~4회(첫 요청 포함) | `REQUESTED` 유지, 결과 모름 횟수 증가 | 201 (`REQUESTED`) |
| 결과 모름 5회째 | `MANUAL_REVIEW` (`RETRY_EXHAUSTED`), **항목 유지** | (복구 스케줄러에서 일어남) |
| 받아들이지 않음(그 밖의 4xx) | `MANUAL_REVIEW` (`LEDGER_REQUEST_NOT_ACCEPTED`), **항목 유지** | 201 (`MANUAL_REVIEW`) |

- 거절되면 항목을 지우므로 포함됐던 결제는 다음 요청에서 다시 대상이 됩니다.
- 결과를 모르는 정산을 `FAILED`로 끝내지 않습니다. 원장에 반영됐을 수 있는데 항목을 지우면 같은 결제가 두 번 정산되기 때문입니다. `REQUESTED`로 남은 정산은 [복구 스케줄러](#복구-스케줄러)가 같은 정산 ID로 다시 보내고, 결과 모름 5회 또는 원장이 받아들이지 않으면 결제·환불과 같이 `MANUAL_REVIEW`로 멈춥니다.
- `MANUAL_REVIEW` 정산은 항목을 그대로 둡니다. 그래서 포함된 결제는 다른 정산에 들어가지 않고 환불도 409 `PAYMENT_ALREADY_SETTLED`입니다. [운영자 처리](#정산-운영자-처리)로 `PAID` 또는 `FAILED`(항목 삭제)가 됩니다.
- 받을 지갑 ID(`owner_wallet_id`)는 정산에 저장해 두고 재전송도 이 값으로 보냅니다.

### 응답

```json
{
  "success": true,
  "data": {
    "settlementId": "0f3a8c21-6d4e-4b7a-9c15-2e8d7f1b4a60",
    "merchantId": "9b2f3c1e-8a47-4d55-b0c2-7e1f6a9d3b10",
    "status": "PAID",
    "amount": 13000,
    "currency": "KRW",
    "ownerUserId": "22222222-2222-2222-2222-222222222222",
    "paymentCount": 2,
    "cutoffAt": "2026-11-12T03:00:00.000000Z",
    "ledgerTransactionId": "7c1e9a4b-3f28-4d6a-8b05-1a9e2c7d6f43",
    "failureReason": null,
    "createdAt": "2026-11-12T03:00:00.000000Z",
    "updatedAt": "2026-11-12T03:00:00.250000Z"
  },
  "error": null
}
```

`cutoffAt`은 요청 시각으로, (승인 시각 + 환불 기간 + 여유 시간)이 이 시각 전인 결제가 들어갔다는 뜻입니다.

### 정산 상태

| 상태 | 의미 |
|---|---|
| `REQUESTED` | 원장 결과 미확정. 복구 스케줄러가 다시 요청 |
| `PAID` | 주인 지갑으로 입금 완료. 포함된 결제는 더 이상 환불할 수 없음 |
| `FAILED` | 원장 거절. 항목이 지워져 포함됐던 결제는 다시 정산 대상 |
| `MANUAL_REVIEW` | 자동 처리 중단(결과 모름 5회·원장이 받아들이지 않음). 항목 유지. 사유(`review_reason`)를 저장하고 [운영자 처리](#정산-운영자-처리)로 해소 |

### 정산 멱등성

| 상황 | 결과 |
|---|---|
| 같은 키·같은 가맹점, 정산 `PAID`·`REQUESTED`·`MANUAL_REVIEW` | 201, 현재 정산 |
| 같은 키·같은 가맹점, 정산 `FAILED` | 처음과 같은 422 |
| 같은 키·다른 가맹점 | 409 `PAYMENT_SETTLEMENT_IDEMPOTENCY_KEY_REUSED` |
| 같은 키 동시 요청 | 하나만 저장되고 나머지는 위 규칙으로 응답 |
| 다른 키로 동시 요청해 같은 결제를 먼저 정산함 | 늦은 쪽 409 `COMMON_CONFLICT` |

- 재요청은 원장을 다시 호출하지 않습니다. 결과 모름 재전송은 복구 스케줄러만 합니다.
- 중복 방지는 `UNIQUE(requested_by, idempotency_key)`, 결제 한 건의 중복 정산 방지는 `settlement_items`의 PK(`payment_id`)로 보장합니다.

### 정산 목록·상세

- 목록은 최신순(`createdAt desc, id desc`)이고 `page`·`size`(최대 100) 규칙은 결제 목록과 같습니다. 항목은 정산 응답과 같으며 `paymentCount`가 들어 있습니다.
- 상세는 `{"settlement": 정산 응답, "items": [{"paymentId", "amount"}]}`입니다. `amount`는 그 결제에서 정산된 금액(결제 금액 − 완료된 환불)입니다. `FAILED` 정산은 항목을 지워 `items`가 비어 있습니다.
- 다른 가맹점의 정산 ID를 넣으면 404 `PAYMENT_SETTLEMENT_NOT_FOUND`입니다.

### 환불과 정산이 겹칠 때

| 상황 | 결과 |
|---|---|
| 정산된 결제에 환불 실행(`MASTER` 직접 환불 포함) | 409 `PAYMENT_ALREADY_SETTLED` |
| 정산과 `MASTER` 직접 환불이 같은 결제에 동시에 들어옴 | 정산은 대상 결제를 `FOR UPDATE`로, 환불은 결제를 잠금 조회로 읽습니다. 정산이 먼저 잠그면 환불은 정산 커밋을 기다린 뒤 정산 여부를 다시 보고 409. 환불이 먼저 잠그면 결제가 `REFUND_PENDING`이 되어 정산에서 빠집니다 |
| 환불 신청 중(`REFUND_REQUESTED`)인 결제 | 정산 대상 아님. 거절·취소로 `APPROVED`가 되면 다음 요청에 포함 |
| `MANUAL_REVIEW` 정산에 묶인 결제 | 다른 정산 대상·미리보기에서 빠지고 환불은 409. 운영자가 `FAILED`로 처리하면 다시 대상이 되고 환불도 가능 |

## 상태

### 결제 상태

```txt
PENDING ──원장 반영──▶ APPROVED ──고객 신청──▶ REFUND_REQUESTED ──가맹점 실행──▶ REFUND_PENDING ──전액 환불 완료──▶ REFUNDED
                               ◀──고객 취소·가맹점 거절──┘                       ──부분 환불 완료·환불 거절──▶ APPROVED
                      APPROVED ──MASTER 직접 환불──────────────────────────────▶ REFUND_PENDING
        ──원장 거절──▶ DECLINED
        ──결과 모름 5회 / 원장이 요청을 받지 않음──▶ MANUAL_REVIEW ──운영자 처리──▶ APPROVED / DECLINED
```

| 상태 | 의미 |
|---|---|
| `PENDING` | 원장 처리 결과 미확정. 복구 스케줄러가 다시 요청 |
| `APPROVED` | 결제 완료. 환불 가능 |
| `DECLINED` | 원장 거절. 거절 사유(`decline_reason`)를 저장 |
| `REFUND_REQUESTED` | 고객이 환불을 신청해 가맹점 처리를 기다림. 원장 호출 없음, 정산 대상 아님 |
| `REFUND_PENDING` | 환불 한 건이 처리 중 |
| `REFUNDED` | 전액 환불 완료 |
| `MANUAL_REVIEW` | 자동 처리 중단. 사유(`LEDGER_REQUEST_NOT_ACCEPTED`·`RETRY_EXHAUSTED`)를 저장. [운영자 처리](#manual_review-운영자-처리)로 해소 |

### 환불 상태

| 상태 | 의미 |
|---|---|
| `REQUESTED` | 원장 처리 결과 미확정. 복구 스케줄러가 다시 요청 |
| `COMPLETED` | 환불 완료. 원장 거래 ID 저장 |
| `DECLINED` | 원장 거절. 거절 사유 저장 |
| `MANUAL_REVIEW` | 자동 처리 중단. 결제는 `REFUND_PENDING`에 머무름. 운영자 처리로 `COMPLETED`·`DECLINED`·`REQUESTED`(재시도)가 됨 |

### 상태 이력

결제의 상태가 바뀔 때마다 `payment_histories`에 이전·현재 상태, 사유, 처리 주체(`USER`·`MERCHANT`·`SYSTEM`·`MASTER`)를 남깁니다. 결제 생성과 고객 환불 신청·취소는 `USER`, 원장 결과 반영은 `SYSTEM`, 신청 거절과 환불 실행은 가맹점 주인이면 `MERCHANT`·`MASTER`면 `MASTER`, 운영자 처리는 `MASTER`입니다. 신청 사유와 거절 사유도 이 이력의 사유에 남습니다. 부분 환불 완료는 사유에 `부분 환불 완료: {금액}`을, 운영자 처리 승인은 `원장에 거래가 반영되어 있음`을 남깁니다. 결제 승인 시각(환불 기간 기준)과 상세 응답의 `refundRequest`도 이 이력에서 읽습니다. 이력 전체를 조회하는 API는 없습니다.

환불 자체의 상태 변화(`REQUESTED → MANUAL_REVIEW` 등)는 결제 상태가 바뀌지 않으면 `payment_histories`에 남지 않습니다. 운영자가 처리한 내용은 처리 기록(`payment_review_resolutions`)에 남습니다.

## 원장 연동과 복구

### 원장 거래 등록

결제·환불·정산 하나를 `POST /internal/v1/ledger/transactions`의 한 거래로 보냅니다. 원장이 두 줄을 함께 반영하거나 함께 거절하므로 한쪽만 반영되지 않습니다.

| 항목 | 결제 | 환불 | 정산 |
|---|---|---|---|
| `referenceType` | `PAYMENT` | `PAYMENT_REFUND` | `SETTLEMENT` |
| `referenceId` | 결제 ID | 환불 ID | 정산 ID |
| `userId` | 결제자 ID | 결제자 ID | 가맹점 주인 ID |
| `originalTransactionId` | 없음 | 원 결제의 원장 거래 ID | 없음 |
| 차감 줄(`DEBIT`) | 결제자 지갑, `PAYMENT_OUT` | 가맹점 지갑, `PAYMENT_REFUND_OUT` | 가맹점 지갑, `SETTLEMENT_OUT` |
| 입금 줄(`CREDIT`) | 가맹점 지갑, `PAYMENT_IN` | 결제자 지갑, `PAYMENT_REFUND_IN` | 주인 지갑, `SETTLEMENT_IN` |

두 줄 모두 `ownerType`은 `USER`이고, 가맹점 지갑 ID는 가맹점 ID입니다. 요청은 저장된 결제·환불·정산 값으로만 만들기 때문에 재전송도 처음과 같은 내용입니다. 원장은 같은 reference를 한 번만 처리하므로 재전송해도 잔액은 한 번만 바뀝니다.

| 원장 응답 | 결과 | 결제 상태 / 환불 상태 / 정산 상태 |
|---|---|---|
| 2xx, `transactionId` 있음 | 반영 | `APPROVED` / `COMPLETED` / `PAID` |
| 422 + `LEDGER_REJECTED_*` 코드 | 거절 | `DECLINED` / `DECLINED` / `FAILED` |
| 그 밖의 4xx(400·409·알 수 없는 422 등) | 받아들이지 않음 | `MANUAL_REVIEW` (`LEDGER_REQUEST_NOT_ACCEPTED`) |
| 읽기 타임아웃·연결 실패·Eureka 미등록·5xx·해석 불가 2xx | 결과 모름 | `PENDING` / `REQUESTED` / `REQUESTED` 유지 |

| 원장 거절 코드 | 결제 거절 사유 | 환불 거절 사유 | 정산 거절 사유 |
|---|---|---|---|
| `LEDGER_REJECTED_INSUFFICIENT_BALANCE` | 잔액이 부족합니다. | 환불이 거절되었습니다. | 가맹점 잔액이 부족합니다. |
| `LEDGER_REJECTED_WALLET_SUSPENDED` | 사용할 수 없는 지갑입니다. | 환불할 수 없는 계정입니다. | 정산 받을 지갑을 사용할 수 없습니다. |
| `LEDGER_REJECTED_ACCOUNT_NOT_FOUND` | 결제할 수 있는 계정이 없습니다. | 환불할 수 있는 계정이 없습니다. | 정산할 수 있는 계정이 없습니다. |
| 그 밖의 `LEDGER_REJECTED_*` | 결제가 거절되었습니다. | 환불이 거절되었습니다. | 정산이 거절되었습니다. |

- 타임아웃이나 5xx는 원장이 이미 처리했을 수 있으므로 실패로 단정하지 않습니다.
- 409는 같은 reference의 원장 거래가 이미 있다는 뜻일 수 있어 `DECLINED`로 끝내지 않습니다. 받아들이지 않음은 ERROR 로그를 남깁니다.
- 원장 Feign 호출은 자동 재시도를 끕니다. 타임아웃은 연결 1초, 읽기 6초입니다(원장 최대 응답 약 5초).

### 복구 스케줄러

`PendingSettlementScheduler`가 결과 모름으로 남은 `PENDING` 결제, `REQUESTED` 환불, `REQUESTED` 정산을 같은 reference로 원장에 다시 보냅니다. 송금·지갑 충전 복구와 같은 방식입니다.

| 항목 | 값 |
|---|---|
| 주기 | 10초마다(시작 10초 후부터). 한 주기에 결제 복구 → 환불 복구 → 정산 복구 순서 |
| 한 주기 처리량 | 재시도 시각이 지난 건을 수정 시각 오래된 순으로 10건씩(배치). 배치가 가득 차면 결제 → 환불 → 정산 순서로 돌아가며 이어서 처리하고, 남은 게 없거나 **8초**가 지나면 그 주기를 끝냄. 평소(10건 미만)에는 종류마다 한 번씩만 조회 |
| 재시도 간격 | 결과 모름 횟수가 n이면 30초 × 2^(n−1) → 30초, 1분, 2분, 4분 (처음 저장 뒤 첫 재시도는 30초 후) |
| 한도 | 결제·환불·정산 모두 결과 모름 5회(첫 요청 포함)면 `MANUAL_REVIEW`(`RETRY_EXHAUSTED`) |
| 원장 장애 판단 | 한 배치에서 결과 모름이 연달아 3번이면 그 종류는 이번 주기에 더 처리하지 않고 다음 주기로(장애 중인 원장에 계속 보내지 않음) |
| 끄기 | `payment.settlement-recovery.enabled=false` (테스트·수동 확인용) |

- 선점: 조회한 건의 상태·수정 시각이 그대로일 때만 조건부 UPDATE로 가져갑니다. 여러 인스턴스가 함께 돌아도 같은 건을 동시에 처리하지 않습니다. 결과 반영은 결제·정산 `@Version`과 상태 조건으로 한 번만 됩니다. 분산 락은 쓰지 않습니다.
- 정산 요청 응답 처리와 복구가 같은 정산의 결과를 동시에 반영하면 늦은 쪽은 먼저 반영된 정산을 다시 읽어 응답합니다.
- 한 건의 오류는 로그만 남기고 다음 건을 계속 처리합니다. 결제 복구가 실패해도 환불·정산 복구는 돕니다.
- payment-service의 스케줄러 스레드는 2개입니다(`spring.task.scheduling.pool.size`). 송금 복구와 서로의 원장 호출을 기다리지 않게 하기 위함입니다.

| 지표 | 태그 | 의미 |
|---|---|---|
| `payment.settlement.stuck` | `type=payment`·`refund`·`settlement` | 생성 후 30분이 지나도 `PENDING` 결제·`REQUESTED` 환불·`REQUESTED` 정산 수. 0보다 크면 ERROR 로그(오래된 순 ID 10개)도 남김 |
| `payment.settlement.manual_review` | `type=payment`·`refund`·`settlement` | 운영자 확인을 기다리는 `MANUAL_REVIEW` 건수 |
| `payment.refund_request.overdue` | 없음 | 고객이 신청한 뒤 3일 넘게 가맹점이 처리하지 않은 환불 신청 수 |

지표는 `/actuator/prometheus`로 볼 수 있습니다. `MANUAL_REVIEW` 건은 아래 [운영자 처리](#manual_review-운영자-처리)로 해소합니다.

## MANUAL_REVIEW 운영자 처리

자동 처리를 멈춘 결제·환불·정산을 `MASTER`가 정리하는 API입니다. **처리 결과는 운영자가 고르지 않고, 서버가 원장 조회 결과로 정합니다.** 운영자는 단건 조회로 예상 결과를 확인한 뒤 처리를 실행하고, 누가 언제 처리했는지가 처리 기록에 남습니다.

```txt
목록 조회 → 단건 조회(원장 조회 + 예상 결과) → 처리(memo 선택)
```

### 목록

- 결제·환불 목록을 따로 둡니다. `reviewReason`(`RETRY_EXHAUSTED`·`LEDGER_REQUEST_NOT_ACCEPTED`)으로 거를 수 있고, 오래된 순으로 정렬합니다. `size`는 최대 100입니다.
- 결제: `paymentId`·`userId`·`merchantId`·금액·통화·`reviewReason`·`attemptCount`·생성·수정 시각
- 환불: `refundId`·`paymentId`·`customerUserId`(결제한 고객)·`merchantId`·`requesterUserId`(환불을 실행한 가맹점 주인·`MASTER`)·금액·통화·`reviewReason`·`attemptCount`·시각

### 단건 조회

`MANUAL_REVIEW`인 건만 볼 수 있습니다(아니면 409 `PAYMENT_NOT_MANUAL_REVIEW`). 서버가 원장을 조회해 처리 API와 **같은 규칙**으로 예상 결과를 계산합니다. 환불 단건에는 결제 금액과 이 환불을 뺀 완료 환불 합계(`refundedAmount`)도 나옵니다.

```json
{
  "paymentId": "...", "amount": 10000, "currency": "KRW",
  "reviewReason": "RETRY_EXHAUSTED", "attemptCount": 5,
  "ledgerLookupFailed": false,
  "ledger": { "status": "POSTED", "transactionId": "...", "declineReason": null },
  "expected": {
    "resolvable": true, "status": "APPROVED", "paymentStatus": "APPROVED",
    "description": "재시도 한도 초과, 원장 반영 확인 → 결제 승인"
  }
}
```

- 원장 조회에 실패하면 503이 아니라 200으로 정보를 보여 주고 `ledgerLookupFailed: true`, `ledger`·`expected`는 `null`입니다.
- `expected.resolvable`이 `false`면 처리할 수 없는 건입니다(아래 원장 보정 필요). `ledger.transactionId`로 원장 거래를 확인합니다.

### 처리 규칙

| 원장 조회 결과 | `RETRY_EXHAUSTED` | `LEDGER_REQUEST_NOT_ACCEPTED` |
|---|---|---|
| `POSTED`, 항목이 우리 기록과 같음 | 결제 `APPROVED` / 환불 `COMPLETED` | 결제 `APPROVED` / 환불 `COMPLETED` |
| `POSTED`, 항목이 다름 | 409 `PAYMENT_LEDGER_CORRECTION_REQUIRED` | 409 `PAYMENT_LEDGER_CORRECTION_REQUIRED` |
| `REJECTED` | 결제 `DECLINED`(원장 거절 사유) / 환불 `DECLINED`(원장 거절 사유) | 결제 `DECLINED`("결제 처리 중 오류로 취소되었습니다.") / 환불 `DECLINED`("환불 처리 중 오류로 취소되었습니다.") |
| 기록 없음 | 결제 `DECLINED`("결제 처리 시간이 초과되어 취소되었습니다.") / **환불 `REQUESTED`(재시도)** | 결제 `DECLINED` / 환불 `DECLINED` (위와 같은 문구) |

- **항목 비교**: 원장이 `POSTED`면 거래 항목(지갑·통화·방향·금액·항목 유형)이 우리 기록으로 만든 기대 항목과 정확히 같은 묶음인지 봅니다(순서 무관). 기대 항목은 [원장 거래 등록](#원장-거래-등록)과 같은 구성이고, 가맹점 쪽은 가맹점 원장 지갑입니다. 같으면 돈이 의도대로 움직인 것이라 멈춘 사유와 상관없이 기록을 맞춥니다(송금 resolve와 같은 정책).
- **항목이 다르면 처리하지 않습니다.** 돈이 우리 기록과 다르게 움직인 상태라 거절로 끝내면 돈과 기록이 어긋납니다. 원장 보정(되돌리는 거래)과 원인 조사가 필요합니다.
- **결제는 기록이 없으면 재시도하지 않습니다.** 오래 멈춘 결제를 뒤늦게 처리하면 고객이 이미 다른 수단으로 냈을 수 있어(이중 결제) 취소합니다. 환불은 돌려줘야 할 돈이라 `REQUESTED`·`attempt_count` 0으로 되돌리고, 복구 스케줄러가 30초 뒤부터 다시 요청합니다.
- 환불 처리 후 결제는 남은 금액이 0이면 `REFUNDED`, 아니면 `APPROVED`(부분 환불), 거절이면 `APPROVED`, 재시도면 `REFUND_PENDING`입니다.

### 처리 요청과 기록

- 본문은 선택이며 `{"memo": "..."}`(255자 이하, 운영자 메모)만 받습니다. 고객에게 보이는 거절 사유에는 들어가지 않습니다.
- 응답: `status`(처리 대상의 바뀐 상태), `paymentStatus`(처리 후 결제 상태), `ledgerStatus`, `ledgerTransactionId`
- 상태가 바뀐 처리만 `payment_review_resolutions`에 남깁니다: `payment_id`·`refund_id`(환불일 때)·`review_reason`(멈췄던 사유)·`ledger_status`·`ledger_transaction_id`·`to_status`·`actor_id`(처리한 `MASTER`)·`description`(시스템이 남기는 처리 설명)·`memo`·`created_at`. 409·503으로 끝난 시도는 남기지 않습니다.
- 결제 상태가 바뀌면 `payment_histories`에도 `MASTER`로 남습니다. 처리 후 `review_reason`은 지워지므로(`MANUAL_REVIEW`일 때만 있음) 멈췄던 사유는 처리 기록에서 봅니다.
- 처리 기록은 `GET /api/v1/admin/payments/resolutions`로 최신순으로 봅니다. 조건은 모두 선택입니다: `type`(`PAYMENT`·`REFUND`), `actorId`(처리한 `MASTER`), `paymentId`(그 결제와 환불의 기록), `page`·`size`(최대 100). 처리 기록 한 줄에 모든 정보가 있어 단건 조회는 따로 없습니다.
- 원장 조회는 트랜잭션 밖에서 하고, 상태 변경·이력·처리 기록은 한 트랜잭션으로 저장합니다. 동시에 처리하면 결제는 `@Version`, 환불은 `status = MANUAL_REVIEW` 조건부 UPDATE로 하나만 반영되고 나머지는 409 `PAYMENT_NOT_MANUAL_REVIEW`입니다.

### 정산 운영자 처리

결제·환불과 같은 방식입니다(목록 → 단건 조회 → 처리, 결과는 원장 조회로 서버가 정함). API는 `/api/v1/admin/payments/settlements` 아래에 따로 둡니다.

- 목록(`/manual-reviews`): `reviewReason`으로 거르기, 오래된 순, `size` 최대 100. 항목은 `settlementId`·`merchantId`·`ownerUserId`·금액·통화·`reviewReason`·`attemptCount`·생성·수정 시각
- 단건(`/{settlementId}`): `{"settlement": 목록 항목, "paymentCount", "ledgerLookupFailed", "ledger": {status, transactionId, declineReason}, "expected": {resolvable, status, description}}`. 원장 조회 실패면 200에 `ledgerLookupFailed: true`
- 처리(`/{settlementId}/resolve`): 본문 `{"memo"}` 선택. 응답 `settlementId`·`status`(`PAID`·`FAILED`)·`failureReason`·`ledgerStatus`·`ledgerTransactionId`
- 없는 정산은 404 `PAYMENT_SETTLEMENT_NOT_FOUND`, `MANUAL_REVIEW`가 아니면 409 `PAYMENT_NOT_MANUAL_REVIEW`

| 원장 조회 결과 | `RETRY_EXHAUSTED` | `LEDGER_REQUEST_NOT_ACCEPTED` |
|---|---|---|
| `POSTED`, 항목이 우리 기록과 같음 | `PAID` (원장 거래 ID 저장) | `PAID` |
| `POSTED`, 항목이 다름 | 409 `PAYMENT_LEDGER_CORRECTION_REQUIRED` | 409 `PAYMENT_LEDGER_CORRECTION_REQUIRED` |
| `REJECTED` | `FAILED`(원장 거절 사유를 [정산 문구](#원장-거래-등록)로) + 항목 삭제 | `FAILED`("정산 처리 중 오류로 취소되었습니다.") + 항목 삭제 |
| 기록 없음 | `FAILED`("정산 처리 시간이 초과되어 취소되었습니다.") + 항목 삭제 | `FAILED`("정산 처리 중 오류로 취소되었습니다.") + 항목 삭제 |

- 기대 항목은 가맹점 원장 지갑 DEBIT `SETTLEMENT_OUT`, 정산에 저장된 주인 지갑 CREDIT `SETTLEMENT_IN`(통화·금액은 정산과 같음)입니다.
- **정산은 기록이 없으면 재시도하지 않고 `FAILED`입니다.** 환불과 달리 가맹점 주인이 언제든 다시 정산을 요청할 수 있고, 새 요청은 주인 지갑을 다시 조회하므로 그 사이 바뀐 지갑에도 안전합니다.
- `FAILED`면 항목을 지워 포함됐던 결제가 다음 정산 요청에서 다시 대상이 되고 환불도 가능해집니다. `PAID`면 항목을 그대로 둡니다.
- 상태가 바뀐 처리만 `settlement_review_resolutions`에 남깁니다(`settlement_id`·`review_reason`·`ledger_status`·`ledger_transaction_id`·`to_status`·`actor_id`·`description`·`memo`·`created_at`). 처리 기록은 `GET /api/v1/admin/payments/settlements/resolutions`(조건 선택: `actorId`·`settlementId`, 최신순)로 봅니다.
- 동시에 처리하면 정산 `@Version`으로 하나만 반영되고 나머지는 409 `PAYMENT_NOT_MANUAL_REVIEW`입니다.

### 원장 조회

`GET /internal/v1/ledger/transactions?referenceType=PAYMENT|PAYMENT_REFUND|SETTLEMENT&referenceId=결제ID|환불ID|정산ID`로 조회합니다.

| 원장 응답 | 판단 |
|---|---|
| 200 `POSTED` + 거래 항목 | 반영됨(항목 비교) |
| 200 `REJECTED` | 거절됨. `rejectReason`(`INSUFFICIENT_BALANCE` 등)을 [거절 사유 표](#원장-거래-등록)로 바꿈 |
| 404 + `LEDGER_TRANSACTION_NOT_FOUND` | 기록 없음 |
| 코드 없는 404(경로 오류 등), 그 밖의 4xx·5xx, 연결 실패, `POSTED`인데 항목·금액 없음, 알 수 없는 상태 | 결론을 내지 않음 → 처리 503 `PAYMENT_LEDGER_LOOKUP_UNAVAILABLE`, 상태 유지 |

"기록 없음"은 결제를 취소하는 근거라, 원장이 그 코드로 답한 경우만 기록 없음으로 봅니다.

## 다른 서비스 호출

| 대상 | API | 결제 쪽 처리 |
|---|---|---|
| wallet-service | `GET /internal/v1/wallets?userId=` | `{walletId, status}`. 결제자 지갑(`ACTIVE`만 결제 가능)과 정산 받을 가맹점 주인 지갑(`ACTIVE`만 정산 가능). 404 + `WALLET_NOT_FOUND`일 때만 지갑 없음 |
| user-service | `GET /internal/v1/users/{userId}/real-name` (I02) | 200이면 `ACTIVE` 회원. 404 + `USER_RECIPIENT_NOT_FOUND`일 때만 회원 아님. 응답 `userId`가 요청과 다르면 502. 실명은 쓰지 않음 |
| ledger-service | `POST /internal/v1/ledger/accounts` | 가맹점 원장 계정 생성. 응답에 가맹점 통화 계정이 없으면 502 |
| ledger-service | `GET /internal/v1/ledger/accounts?walletId=` | 가맹점 잔액. 가맹점 통화 계정이 없으면 502 |
| ledger-service | `POST /internal/v1/ledger/transactions` | [원장 거래 등록](#원장-거래-등록) |
| ledger-service | `GET /internal/v1/ledger/transactions?referenceType=&referenceId=` | 운영자 처리의 [원장 조회](#원장-조회). 송금 resolve와 같은 `LedgerFeignApi.getTransaction` |

지갑·회원 호출은 송금과 같은 Feign 클라이언트(`WalletFeignApi`·`UserFeignApi`)를 쓰며 타임아웃은 연결 2초, 읽기 3초입니다. 원장 계정 호출은 거래 등록과 같은 클라이언트라 연결 1초, 읽기 6초입니다. 사용자 요청 중의 호출에는 common `InternalHeaderRelayInterceptor`가 요청자의 `User-Id`·`User-Role`을 붙입니다.

거래 등록을 뺀 호출의 실패는 common `FeignErrors.toCommonException()` 규칙을 따릅니다.

| 호출 실패 | 응답 |
|---|---|
| 연결 실패, Eureka 미등록, 상대 503 | 503 `COMMON_SERVICE_UNAVAILABLE` |
| 읽기 타임아웃, 상대 504 | 504 `COMMON_GATEWAY_TIMEOUT` |
| 상대 429 + `COMMON_TOO_MANY_REQUESTS` | 429 `COMMON_TOO_MANY_REQUESTS` |
| 위 목록에 없는 4xx·5xx, 해석할 수 없는 응답 | 502 `COMMON_BAD_GATEWAY` |

- 결제의 지갑 확인 단계에서 실패하면 결제는 저장되지 않고 키도 사용되지 않습니다. 같은 키로 다시 요청할 수 있습니다.
- 가맹점 생성의 주인 확인 단계에서 실패하면 가맹점은 저장되지 않습니다. 원장 계정 생성이 실패하면 오류가 아니라 202 `PENDING`입니다.
- user-service I02는 요청자별 호출 한도(기본 60초 60회)가 있습니다. 가맹점 생성은 `MASTER`의 한도를 씁니다.

## 오류 응답

| 상태 | 코드 | 상황 |
|---|---|---|
| 400 | `COMMON_INVALID_REQUEST` | 본문 검증 실패(운영자 처리 `memo` 255자 초과 포함), `Idempotency-Key` 없음·형식 오류, 잘못된 `status`·`reviewReason`·`page`·`size` |
| 400 | `PAYMENT_CURRENCY_MISMATCH` | 결제 통화가 가맹점 통화와 다름 |
| 401 | `COMMON_UNAUTHORIZED` | 회원 헤더 없음(Gateway 경유 시 세션 없음) |
| 403 | `COMMON_CSRF_INVALID` | Gateway 경유 변경 요청(POST·DELETE)의 CSRF 토큰 없음·불일치 |
| 403 | `COMMON_FORBIDDEN` | `MASTER`가 아닌 회원의 가맹점 생성·원장 계정 재시도·운영자 처리 API |
| 404 | `PAYMENT_MERCHANT_NOT_FOUND` | 가맹점 없음, 가맹점 주인·`MASTER`가 아닌 회원의 가맹점 조회 |
| 404 | `PAYMENT_MERCHANT_OWNER_NOT_FOUND` | 가맹점 생성 시 주인이 활성 회원이 아님 |
| 404 | `PAYMENT_WALLET_NOT_FOUND` | 결제자 지갑 없음 |
| 404 | `PAYMENT_NOT_FOUND` | 결제가 없거나 조회·환불·환불 신청·취소·거절 권한이 없음 |
| 404 | `PAYMENT_REFUND_NOT_FOUND` | 운영자 처리 대상 환불이 없음 |
| 404 | `PAYMENT_SETTLEMENT_NOT_FOUND` | 정산이 없거나 다른 가맹점의 정산, 운영자 처리 대상 정산이 없음 |
| 409 | `PAYMENT_IDEMPOTENCY_KEY_REUSED` | 같은 키로 다른 결제 |
| 409 | `PAYMENT_REFUND_IDEMPOTENCY_KEY_REUSED` | 같은 키로 다른 환불 |
| 409 | `PAYMENT_REFUND_IN_PROGRESS` | 다른 환불이 처리 중 |
| 409 | `PAYMENT_ALREADY_REFUNDED` | 전액 환불된 결제 |
| 409 | `PAYMENT_NOT_REFUNDABLE` | 환불할 수 없는 결제 상태 |
| 409 | `PAYMENT_ALREADY_SETTLED` | 정산에 들어간 결제를 환불(정산 결과 확인 중·운영자 확인 중 포함) |
| 409 | `PAYMENT_SETTLEMENT_IDEMPOTENCY_KEY_REUSED` | 같은 키로 다른 가맹점 정산 |
| 409 | `COMMON_CONFLICT` | 같은 결제에 동시 환불, 환불 신청 취소·거절·실행이 동시에 들어옴, 동시 정산 요청이 같은 결제를 먼저 정산함 |
| 409 | `PAYMENT_NOT_MANUAL_REVIEW` | 운영자 단건 조회·처리 대상이 `MANUAL_REVIEW`가 아님, 또는 동시에 다른 처리가 먼저 끝남 |
| 409 | `PAYMENT_LEDGER_CORRECTION_REQUIRED` | 원장 `POSTED` 거래의 항목이 우리 결제·환불·정산과 달라 처리할 수 없음 |
| 422 | `PAYMENT_MERCHANT_NOT_ACTIVE` | 가맹점이 `ACTIVE`가 아님(결제), `PENDING`(잔액 조회·정산 요청) |
| 422 | `PAYMENT_SETTLEMENT_WALLET_NOT_AVAILABLE` | 정산 받을 가맹점 주인 지갑이 없거나 `ACTIVE`가 아님 |
| 422 | `PAYMENT_NO_SETTLEMENT_TARGET` | 정산할 결제가 없음 |
| 422 | `PAYMENT_SETTLEMENT_DECLINED` | 원장이 정산 거절(정산 `FAILED`), `details`에 거절 사유 |
| 422 | `PAYMENT_WALLET_NOT_ACTIVE` | 결제자 지갑이 `ACTIVE`가 아님 |
| 422 | `PAYMENT_DECLINED` | 원장이 결제 거절(결제 `DECLINED`), `details`에 거절 사유 |
| 422 | `PAYMENT_REFUND_DECLINED` | 원장이 환불 거절(환불 `DECLINED`), `details`에 거절 사유 |
| 422 | `PAYMENT_REFUND_AMOUNT_EXCEEDED` | 환불 금액이 남은 금액보다 큼 |
| 422 | `PAYMENT_REFUND_PERIOD_EXPIRED` | 고객 환불 신청 때 결제 승인 후 3일이 지남 |
| 422 | `PAYMENT_REFUND_NOT_REQUESTED` | 가맹점 주인이 고객 신청 없는 결제를 환불, 신청 중이 아닌 결제의 신청 취소·거절 |
| 429 | `COMMON_TOO_MANY_REQUESTS` | user-service 회원 조회 한도 초과 |
| 502 | `COMMON_BAD_GATEWAY` | 지갑·회원·원장 계정 응답 해석 불가 |
| 503 | `COMMON_SERVICE_UNAVAILABLE` | 지갑·회원·원장 계정 서비스 연결 불가 |
| 503 | `PAYMENT_LEDGER_LOOKUP_UNAVAILABLE` | 운영자 처리 중 원장 거래 조회 실패(상태는 그대로) |
| 504 | `COMMON_GATEWAY_TIMEOUT` | 지갑·회원·원장 계정 서비스 응답 시간 초과 |

`PAYMENT_INVALID_AMOUNT`(400)와 `PAYMENT_INVALID_STATUS_TRANSITION`(409)은 도메인 검사용으로, 요청 검증을 먼저 하기 때문에 정상 흐름에서는 응답으로 나오지 않습니다. 원장 장애는 오류가 아니라 `PENDING`·`REQUESTED` 상태로 응답합니다(정산도 `REQUESTED`). 장애 메시지나 상대 응답 본문은 클라이언트에 내보내지 않습니다.

## DB 스키마 (Flyway)

결제 테이블은 `payment_service` 스키마의 `merchants`·`payments`·`payment_histories`·`refunds`·`payment_review_resolutions`·`settlements`·`settlement_items`이며, 송금과 같은 `payment-service/src/main/resources/db/migration` 폴더를 씁니다. 파일 이름은 `V{yyyyMMdd}_{순번}__{설명}.sql`입니다. Hibernate는 검사만 합니다(`ddl-auto: validate`).

| 파일 | 내용 |
|---|---|
| `V20261028_2__create_payment_tables.sql` | 가맹점·결제·결제 이력 테이블 |
| `V20261029_2__create_refunds.sql` | 환불 테이블 |
| `V20261030_1__use_timestamptz_for_payment_tables.sql` | 시각 컬럼을 `TIMESTAMPTZ`로 변경(기존 값은 KST로 해석) |
| `V20261031_1__add_merchant_ledger_wallet_and_owner.sql` | 가맹점 `ledger_wallet_id`·`owner_user_id`, `PENDING` 상태 |
| `V20261103_1__add_payment_refund_manual_review.sql` | 결제·환불 `MANUAL_REVIEW`, `attempt_count`, `review_reason` |
| `V20261105_2__add_payment_review_resolutions_and_list_indexes.sql` | 운영자 처리 기록 테이블, 가맹점별·회원별 결제 목록 인덱스 |
| `V20261106_1__add_refund_requested_status_and_status_indexes.sql` | 결제 `REFUND_REQUESTED` 상태, 이력 처리자 `MERCHANT`, 상태별 부분 인덱스 |
| `V20261107_1__create_settlements.sql` | 정산·정산 항목 테이블 |
| `V20261109_1__add_settlement_manual_review.sql` | 정산 `MANUAL_REVIEW` 상태·`review_reason`, 정산 운영자 처리 기록 테이블 |

| 제약·인덱스 | 내용 |
|---|---|
| `uk_payments_user_id_idempotency_key` | `UNIQUE(user_id, idempotency_key)` 중복 결제 방지 |
| `uk_refunds_user_id_idempotency_key` | `UNIQUE(user_id, idempotency_key)` 중복 환불 방지 |
| `ck_payments_amount`, `ck_refunds_amount` | `amount > 0` |
| `ck_*_currency` | `KRW`·`JPY`·`CNY`만 |
| `ck_merchants_status`, `ck_payments_status`, `ck_refunds_status` | 상태 값 목록 |
| `ck_payments_manual_review_has_reason`, `ck_refunds_manual_review_has_reason` | `MANUAL_REVIEW`일 때만 `review_reason`이 있음 |
| `ck_payments_attempt_count`, `ck_refunds_attempt_count` | `attempt_count >= 0` |
| `ck_payment_histories_created_by` | `USER`·`MERCHANT`·`SYSTEM`·`MASTER` |
| `fk_payment_histories_payment_id`, `fk_refunds_payment_id` | 결제 FK |
| `ix_payment_histories_payment_id_created_at`, `ix_refunds_payment_id_created_at` | 결제별 이력·환불 시간순 조회 |
| `idx_merchants_owner_user_id` | 주인별 가맹점(한 회원이 여러 가맹점 가능, UNIQUE 아님) |
| `ix_payments_merchant_id_created_at_id`, `ix_payments_user_id_created_at_id` | 가맹점별·회원별 결제 목록(`created_at desc, id desc`)과 count. 100만 건에서 가맹점 목록 API 약 70ms → 23ms |
| `fk_payment_review_resolutions_payment_id`, `fk_payment_review_resolutions_refund_id` | 처리 기록 → 결제·환불 FK |
| `ck_payment_review_resolutions_ledger_transaction_id` | 원장 기록 없음(`NOT_FOUND`)일 때만 원장 거래 ID가 비어 있음 |
| `ix_payment_review_resolutions_payment_id_created_at` | 결제별 처리 기록 시간순 조회 |
| `ix_payments_pending_updated_at_id`, `ix_payments_manual_review_created_at_id`, `ix_payments_refund_requested_updated_at_id`, `ix_payments_refund_requested_merchant_id_created_at_id`, `ix_refunds_requested_updated_at_id`, `ix_refunds_manual_review_created_at_id` | 상태별 부분 인덱스(해당 상태의 행만 담음). 복구 대상·`MANUAL_REVIEW` 건수·30분 지난 건수, 3일 초과 환불 신청, 가맹점 환불 신청 목록. 로컬 48만 건에서 결제 복구 대상 조회 37.7ms → 0.13ms |
| `uk_settlements_requested_by_idempotency_key` | `UNIQUE(requested_by, idempotency_key)` 중복 정산 방지 |
| `settlement_items` PK(`payment_id`) | 결제 한 건은 한 번만 정산 |
| `ck_settlements_status` | `REQUESTED`·`PAID`·`FAILED`·`MANUAL_REVIEW`(`V20261109_1`에서 추가) |
| `ck_settlements_amount`, `ck_settlement_items_amount`, `ck_settlements_attempt_count` | 금액 `> 0`, `attempt_count >= 0` |
| `ck_settlements_paid_has_ledger_transaction` | `PAID`면 원장 거래 ID가 있음 |
| `fk_settlements_merchant_id`, `fk_settlement_items_payment_id`, `fk_settlement_items_settlement_id` | 가맹점·결제·정산 FK |
| `ix_settlement_items_settlement_id` | 정산 상세의 포함 결제 조회, 거절 시 항목 삭제 |
| `ix_settlements_requested_updated_at_id` | 결과 모름 정산 복구 대상(`REQUESTED` 부분 인덱스) |
| `ix_settlements_merchant_id_created_at_id` | 가맹점별 정산 목록(최신순) |
| `ck_settlements_manual_review_has_reason` | `MANUAL_REVIEW`일 때만 `review_reason`이 있음 |
| `ix_settlements_manual_review_created_at_id` | `MANUAL_REVIEW` 정산 목록·건수(부분 인덱스) |
| `fk_settlement_review_resolutions_settlement_id`, `ck_settlement_review_resolutions_*` | 정산 처리 기록 → 정산 FK, 사유·원장 결과·처리 결과 값 목록, 원장 기록 없음일 때만 거래 ID가 비어 있음 |

- 회원·지갑·원장은 다른 서비스 소유라 FK 없이 ID만 저장합니다. 결제자 지갑 ID를 결제에, 주인 지갑 ID를 정산에 저장해 재전송 요청을 저장된 값만으로 만듭니다.
- 정산 항목은 수백 건이 한 번에 저장될 수 있어 JDBC 배치 INSERT를 켭니다(`hibernate.jdbc.batch_size: 100`, `order_inserts`·`order_updates`, Hikari `reWriteBatchedInserts=true`).
- 정산 여부는 `payments` 상태가 아니라 `settlement_items` 존재로 판단합니다(`SETTLED` 상태 없음).
- `V20261031_1`은 `owner_user_id NOT NULL`을 추가하므로 기존 가맹점 행이 있는 DB에서는 실패합니다. 기존 데이터는 각 환경에서 직접 정리합니다.
- 이미 적용한 파일은 고치지 않고 다음 날짜·순번의 새 파일을 추가합니다. 송금 쪽과 같은 날짜를 쓰면 순번이 겹치지 않게 맞춥니다.
- Flyway는 순서를 벗어난 적용을 허용하지 않습니다(`outOfOrder` 꺼짐). 다른 브랜치에서 나중 버전을 먼저 적용한 로컬 DB는 `Detected resolved migration not applied to database`로 시작을 멈춥니다. 이때 로컬에서만 한 번 `SPRING_FLYWAY_OUT_OF_ORDER=true`로 실행해 빠진 파일을 적용합니다. 설정 파일은 바꾸지 않습니다.

## 테스트

```bash
./gradlew :payment-service:test
```

송금 테스트도 함께 돕니다. 결제 쪽만 돌릴 때는 패키지를 지정합니다.

```bash
./gradlew :payment-service:test \
  --tests "com.travelpay.payment.application.*" \
  --tests "com.travelpay.payment.domain.*" \
  --tests "com.travelpay.payment.infra.*" \
  --tests "com.travelpay.payment.presentation.*"
```

| 테스트 | 확인하는 것 |
|---|---|
| `PaymentServiceTest` | 결제·환불 처리 순서, 멱등성, 부분 환불·남은 금액, 환불 기간, 고객 환불 신청·취소·거절과 역할별 실행 가능 상태, 3일 초과 신청 목록, 결과 모름·`MANUAL_REVIEW`, 복구 |
| `RefundRequestSummaryTest` | 결제 이력으로 고객 환불 신청 요약(`REQUESTED`·`CANCELLED`·`REJECTED`·`ACCEPTED`) 판정 |
| `PaymentHistoryRepositoryImplTest` | 결제별 가장 최근 환불 신청 사유 조회 |
| `ManualReviewRulesTest` | 운영자 처리 규칙(멈춘 사유 × 원장 결과), 원장 항목 비교 |
| `PaymentReviewServiceTest` | 운영자 처리·단건 조회·목록·처리 기록 목록, 예상 결과와 실제 처리 일치, 항목 불일치·동시 처리·원장 조회 실패 |
| `PaymentReviewRepositoryIntegrationTest` | 실제 PostgreSQL에서 목록 쿼리, 환불 조건부 UPDATE, 처리 기록 제약·조건별 조회, 승인 시각 조회, 목록 인덱스 |
| `PaymentTest` | 결제 상태 전이(환불 신청·취소·거절 포함)와 역할별 환불 가능 여부 |
| `MerchantServiceTest` | 가맹점 생성(주인 확인, 원장 실패 시 `PENDING`), 재시도, 잔액 권한 |
| `PaymentLedgerFeignClientTest` | 원장 응답별 반영·거절·받아들이지 않음·결과 모름 분류, 거래 조회 결과 변환(코드 없는 404는 결론 내지 않음) |
| `MerchantLedgerFeignClientTest`, `PaymentWalletFeignClientTest`, `MerchantUserFeignClientTest` | 다른 서비스 응답·실패 변환 |
| `PaymentExceptionHandlerTest` | 거절 사유를 `details`에 담는지 |
| `PendingSettlementSchedulerTest` | 스케줄러 주기 처리와 지표 |
| `SettlementServiceTest` | 정산 요청(대상 기간·완료 환불 차감·주인 지갑), 멱등성·동시 요청, 거절 시 항목 삭제, 결과 모름·5회째 `MANUAL_REVIEW`·받아들이지 않음(`MANUAL_REVIEW`, 항목 유지), 복구, 목록·상세, 미리보기 |
| `SettlementTest` | 정산 생성·상태 전이(결과는 한 번만 반영), 금액 검증, 같은 요청 판정 |
| `SettlementRepositoryIntegrationTest` | 실제 PostgreSQL에서 정산 대상·미리보기 조회 조건(승인 시각 기준, 정산된·신청 중 결제 제외), 항목 유일성, 복구 조건부 UPDATE, `MANUAL_REVIEW` 전환·목록·제약, 처리 기록 조회 |
| `SettlementReviewServiceTest` | 정산 운영자 처리 규칙(멈춘 사유 × 원장 결과), 예상 결과와 실제 처리 일치, 항목 삭제·유지, 원장 보정 필요·조회 실패·동시 처리 |
| `SettlementRefundLockIntegrationTest` | 정산과 `MASTER` 직접 환불을 커밋하는 두 트랜잭션으로 동시에 실행해 이중 지급이 없는지 |

`PaymentReviewRepositoryIntegrationTest`·`Settlement*IntegrationTest`는 송금 통합 테스트처럼 Testcontainers로 PostgreSQL을 띄우고 Flyway를 처음부터 적용합니다. Docker가 없으면 건너뜁니다. 나머지는 DB 없이 실행되는 단위 테스트입니다.

로컬에서 `MANUAL_REVIEW` 건을 만들 때는 ledger-service를 끈 채로 결제·환불을 요청합니다. 결과 모름 5회(약 8분) 뒤 `RETRY_EXHAUSTED`가 됩니다. 원장 상태(`POSTED`·`REJECTED`)는 ledger-service의 `POST /internal/v1/ledger/transactions`로 같은 reference의 거래를 직접 등록해 만듭니다(잔액보다 큰 금액이면 `REJECTED`).

## Gateway 연결과 신뢰 경계

- 외부 요청은 Gateway `/api/v1/payments/**` 라우트로 들어옵니다. 가맹점 API도 이 경로 아래라 Gateway 라우트를 따로 둘 필요가 없습니다. 운영자 처리 API는 관리자 API 규칙에 따라 `/api/v1/admin/payments/**` 라우트로 들어옵니다. Gateway는 세션·CSRF를 확인하고, 클라이언트가 보낸 `User-Id`·`User-Role`을 지운 뒤 검증한 값으로 다시 설정합니다.
- Gateway 경유 POST·DELETE(결제·환불·환불 신청·취소·거절·가맹점 생성·정산 요청)는 CSRF 토큰이 필요합니다. 로그인하면 세션이 바뀌므로 로그인 뒤 CSRF 토큰을 다시 받습니다.
- payment-service는 회원 헤더를 믿고 요청자와 `MASTER` 여부를 정하므로, 배포에서는 payment-service 포트를 외부에 열지 않아야 합니다. 직접 접근할 수 있으면 헤더를 위조해 `MASTER`로 행세할 수 있습니다.
