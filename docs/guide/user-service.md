# User 서비스 실행·사용 가이드

user-service는 일반 회원가입, 로그인·로그아웃, Redis 세션, CSRF, 내 정보 조회, 약관 조회와 내부 세션·회원 이름 조회를 담당합니다. 브라우저는 Gateway를 통해 호출하고, User는 세션의 발급·검증을 담당합니다. Gateway 실행과 프런트·배포 연결은 [Gateway 가이드](gateway.md)를 참고합니다.

## 로컬 실행

필요한 것: JDK 21, Docker.

1. `.env.example`을 `.env`로 복사하고 비밀번호 값을 바꿉니다. 실제 값은 저장소에 올리지 않습니다.
2. DB와 세션 Redis를 실행합니다.

   ```bash
   docker compose up -d postgres redis-session
   ```

3. Spring Boot는 `.env`를 직접 읽지 않으므로 환경 변수로 넘겨 실행합니다.

   ```bash
   set -a; source .env; set +a
   ./gradlew :user-service:bootRun
   ```

   - 로컬 포트는 `8081`입니다. `GET http://localhost:8081/actuator/health`로 상태를 확인합니다.
   - Eureka 서버 없이 user-service만 확인할 때는 `./gradlew :user-service:bootRun --args='--eureka.client.enabled=false'`로 실행합니다.
   - 배포 설정(`application-prod.yml`)은 `SPRING_CONFIG_NAME=application-prod`로 선택합니다.

4. 테이블과 초기 약관은 앱이 시작할 때 Flyway가 만듭니다([DB 스키마](#db-스키마-flyway)). 초기 약관은 `SERVICE_TERMS`·`PRIVACY_TERMS`(버전 `2026-10-01`, 둘 다 필수)입니다.

   Flyway 도입 전에 실행해서 `user_service` 스키마에 표가 이미 있으면 앱이 `Found non-empty schema(s) "user_service" but no schema history table`로 시작을 멈춥니다. 이때는 로컬 `user_service` 스키마를 한 번 새로 만듭니다. 이 스키마의 로컬 데이터는 모두 지워집니다. 세 명령은 한 트랜잭션(`-1`)으로 실행되므로, 중간에 실패하면 아무것도 바뀌지 않습니다.

   ```bash
   docker compose exec -T postgres sh -c 'psql -1 -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
     -c "DROP SCHEMA user_service CASCADE" \
     -c "CREATE SCHEMA user_service AUTHORIZATION \"$USER_DB_USERNAME\"" \
     -c "REVOKE ALL ON SCHEMA user_service FROM PUBLIC"'
   ```

## API 목록

| API | 설명 | 로그인 | CSRF 헤더 | 성공 |
|---|---|---|---|---|
| `GET /api/v1/auth/csrf` | CSRF 토큰 조회, 세션 쿠키 발급 | 불필요 | 불필요 | 200 |
| `GET /api/v1/terms` | 현재 시행 약관 조회 | 불필요 | 불필요 | 200 |
| `POST /api/v1/users` | 일반 회원가입 | 불필요 | 필요 | 201 |
| `POST /api/v1/auth/login` | 일반 로그인 | 불필요 | 필요 | 200 |
| `GET /api/v1/users/me` | 내 정보 조회 | 필요 | 불필요 | 200 |
| `POST /api/v1/auth/logout` | 로그아웃 | 필요 | 필요 | 204 |

응답 본문은 `success`·`data`·`error` 공통 형식입니다([error-contract.md](error-contract.md)). 로그아웃 성공은 본문 없는 204입니다.

## 호출 순서

모든 요청은 같은 `SESSION` 쿠키를 함께 보내야 합니다. 프런트의 API 기준 주소는 로컬 Gateway `http://localhost:8080`, 배포 `https://api.travelpay.store`입니다. CORS는 Gateway에서 처리하고, 프런트는 `credentials: "include"`를 사용합니다. User 직접 호출은 서버 개발·진단용으로 유지합니다.

```txt
1. GET  /api/v1/auth/csrf   → SESSION 쿠키와 { headerName, token } 받기
2. GET  /api/v1/terms       → 약관 표시
3. POST /api/v1/users       → Csrf-Token 헤더 포함, 201 후 로그인 화면으로 이동
4. POST /api/v1/auth/login  → Csrf-Token 헤더 포함, 성공하면 세션 ID가 바뀌고 이전 CSRF 토큰은 버려짐
5. GET  /api/v1/auth/csrf   → 로그인 후 새 CSRF 토큰 받기
6. GET  /api/v1/users/me    → 로그인한 회원 정보
7. POST /api/v1/auth/logout → 새 Csrf-Token 헤더 포함, 세션 삭제와 쿠키 만료
```

로그인 후에 CSRF 토큰을 다시 받지 않으면 이후 변경 요청은 `403 COMMON_CSRF_INVALID`입니다.

curl로 확인할 때는 쿠키 파일을 사용합니다. 아래 비밀번호는 예시이며 실제 비밀번호를 문서나 기록에 남기지 않습니다.

```bash
BASE=http://localhost:8081
TOKEN=$(curl -s -c jar -b jar $BASE/api/v1/auth/csrf | sed 's/.*"token":"\([^"]*\)".*/\1/')

curl -s -c jar -b jar -H "Csrf-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"loginEmail":"member@example.com","password":"TravelpayDemo-2026!","realName":"홍길동","phone":"010-1234-5678","consents":[{"termsCode":"SERVICE_TERMS","termsVersion":"2026-10-01"},{"termsCode":"PRIVACY_TERMS","termsVersion":"2026-10-01"}]}' \
  $BASE/api/v1/users

curl -s -c jar -b jar -H "Csrf-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"loginEmail":"member@example.com","password":"TravelpayDemo-2026!"}' \
  $BASE/api/v1/auth/login

TOKEN=$(curl -s -c jar -b jar $BASE/api/v1/auth/csrf | sed 's/.*"token":"\([^"]*\)".*/\1/')
curl -s -b jar $BASE/api/v1/users/me
curl -s -c jar -b jar -H "Csrf-Token: $TOKEN" -X POST -o /dev/null -w '%{http_code}\n' $BASE/api/v1/auth/logout
```

## 내부 API

서버끼리 호출하는 API입니다. I01은 Gateway가 SESSION 쿠키를 전달하는 세션 확인 API이고, I02·I03은 업무 서비스의 회원 이름 조회입니다. 외부 Gateway 라우트에는 등록하지 않습니다.

### I01 · 세션 확인

| 항목 | 내용 |
|---|---|
| 요청 | `POST /internal/v1/auth/session`, 본문 없음 |
| 입력 | SESSION 쿠키·Original-Method·필요한 Csrf-Token |
| Original-Method | Gateway가 실제 외부 요청 메서드로 작성, 정확히 하나의 대문자 값 |
| 검증 | 로그인 세션·유휴/절대 만료·현재 DB 회원 상태·역할 |
| CSRF | 원래 메서드가 POST·PUT·PATCH·DELETE일 때 검사 |
| 성공 | 200, ApiResponse의 data에 userId·role |
| 실패 | 세션 없음 401, 회원 상태·CSRF 403, 세션 충돌 409, 저장소 장애 503 |

허용하는 원래 메서드는 GET·HEAD·OPTIONS·POST·PUT·PATCH·DELETE입니다. 잘못된 Original-Method는 400이며, Gateway는 이 오류를 만들지 않도록 실제 메서드를 검사해 I01을 호출합니다. 성공 응답은 쿠키를 새로 발급하지 않으며, 기존 세션의 접근 시각은 갱신됩니다.

```json
{
  "success": true,
  "data": {
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "role": "USER"
  },
  "error": null
}
```

### I02·I03 · 회원 이름 조회

세션 쿠키와 CSRF 헤더 없이 호출하며, 응답에서 세션을 만들지 않습니다. 대신 아래 두 회원 헤더가 모두 필요합니다.

| 헤더 | 값 |
|---|---|
| `User-Id` | Gateway가 검증한 요청자의 회원 UUID. 경로에 있는 조회 대상 ID와 구분합니다. |
| `User-Role` | 요청자의 역할인 `USER` 또는 `MASTER` |

헤더가 없거나 일부만 있거나, 값이 잘못됐거나 중복되면 `401 COMMON_UNAUTHORIZED`입니다. User 자신의 보호 API는 기존 세션 Provider를 사용하고, 이 두 내부 조회 API의 컨트롤러만 회원 헤더를 직접 검사합니다. `header-context.enabled`를 다시 켜지 않습니다.

| API | 명세 | 용도 |
|---|---|---|
| `GET /internal/v1/users/{userId}/real-name` | I02 | 송금 수취인 확인 |
| `GET /internal/v1/users/{userId}/funding-identity` | I03 | 충전 예금주 확인(충전 요청자 본인) |

두 API의 성공 응답 형식은 같습니다. `realName`은 가입 때 입력한 성명이며 실명 확인 결과가 아닙니다. 이메일·휴대폰 번호는 담지 않으며, 화면에 보여 줄 이름의 마스킹은 호출한 서비스의 공개 응답에서 합니다.

```json
{
  "success": true,
  "data": {
    "userId": "4c6a0178-266b-4f1e-b811-a2767026be70",
    "realName": "홍길동"
  },
  "error": null
}
```

| 상황 | I02 `real-name` | I03 `funding-identity` |
|---|---|---|
| 회원 헤더 누락·잘못된 형식·중복 | 401 `COMMON_UNAUTHORIZED` | 401 `COMMON_UNAUTHORIZED` |
| 요청자 ID와 조회 대상 ID가 다름 | 다른 수취인 조회 가능 | DB 조회 전 403 `COMMON_FORBIDDEN` |
| ACTIVE 회원 | 200 | 200 |
| 회원 없음 | 404 `USER_RECIPIENT_NOT_FOUND` | 404 `USER_NOT_FOUND` |
| 정지·종료 회원 | 404 `USER_RECIPIENT_NOT_FOUND` | 403 `USER_ACCOUNT_SUSPENDED`, `USER_ACCOUNT_CLOSED` |
| `userId`가 UUID 형식이 아님 | 400 `COMMON_INVALID_REQUEST` | 400 `COMMON_INVALID_REQUEST` |
| 요청자의 조회 한도 소진 | 429 `COMMON_TOO_MANY_REQUESTS` | I02 한도를 적용하지 않음 |
| 조회 횟수 저장소(Redis) 장애 | 503 `COMMON_SERVICE_UNAVAILABLE` | I02 횟수 저장소를 사용하지 않음 |
| DB 장애 | 503 `COMMON_SERVICE_UNAVAILABLE` | 503 `COMMON_SERVICE_UNAVAILABLE` |

대상 회원의 상태에 따른 200·404·403은 정상 회원 헤더를 받았고, I02는 호출 한도를 통과했으며, I03은 요청자와 대상이 같은 경우의 결과입니다.

I02는 다른 회원을 조회하므로 없는 회원과 정지·종료 회원을 같은 404로 응답해 상태를 알려 주지 않습니다. I03은 요청자 본인만 조회하며 `MASTER`도 예외로 다른 회원을 조회할 수 없습니다. 본인 확인을 통과한 뒤에는 정지·종료 상태를 해당 회원에게 403으로 알려 줍니다.

접근 보호는 팀 결정(2026-10-31)에 따라 내부망 격리만 사용하며, 서비스 인증 헤더는 없습니다. user-service는 위 두 경로의 GET만 세션 없이 허용하고, 그 밖의 `/internal/**` 요청은 다른 API와 같은 로그인·CSRF 규칙을 따릅니다. 외부에서 닿지 않게 막는 일은 Gateway 라우트와 배포 망 구성이 맡습니다. 로컬 실행에서는 `8081` 포트로 바로 호출할 수 있습니다.

```bash
# REQUESTER_ID는 요청자의 UUID, RECIPIENT_ID는 조회할 수취인의 UUID입니다.
curl -s -H "User-Id: $REQUESTER_ID" -H 'User-Role: USER' \
  "$BASE/internal/v1/users/$RECIPIENT_ID/real-name"

# I03은 요청자 UUID와 경로의 UUID가 같아야 합니다.
curl -s -H "User-Id: $REQUESTER_ID" -H 'User-Role: USER' \
  "$BASE/internal/v1/users/$REQUESTER_ID/funding-identity"
```

#### I02 요청자별 호출 제한

기본 한도는 **요청자 한 명당 최근 60초 동안 허용된 조회 60회**입니다. 조회 대상이 달라져도 같은 요청자의 한도를 사용합니다.

- 세션 Redis의 별도 키에 조회 허용 시각을 기록합니다. 회원 DB 행이나 조회 응답을 저장·삭제하는 기능이 아닙니다.
- 집계 기간이 지난 기록은 빼고 최근 기록만 셉니다. 허용한 호출은 대상 조회가 200·404 등 어떤 결과로 끝나든 한 번 집계합니다.
- 한도를 소진한 요청은 DB 조회 전에 429로 종료합니다. 거절된 호출을 기록에 추가하거나 기록의 만료 시간을 연장하지 않습니다.
- Redis를 사용할 수 없으면 제한 없이 진행하지 않고 DB 조회 전에 503으로 종료합니다. Redis 확인은 이 서비스가 시작하는 읽기 트랜잭션 밖에서 수행합니다.
- I03 본인 조회에는 I02 호출 한도를 적용하지 않습니다.

| 설정 | 환경변수 | 기본값 |
|---|---|---|
| `travelpay.user.recipient-lookup.limit` | `USER_RECIPIENT_LOOKUP_LIMIT` | `60` |
| `travelpay.user.recipient-lookup.period` | `USER_RECIPIENT_LOOKUP_PERIOD` | `60s` |

로컬·배포 모두 같은 기본값을 사용합니다. 횟수는 1 이상, 기간은 1초 이상이며 Redis에서 안전하게 계산할 수 있는 범위여야 합니다. 잘못된 설정은 앱 시작 시 거절합니다. 환경변수 변경은 앱 재시작·배포 후 적용됩니다.

호출 서비스는 `429 + COMMON_TOO_MANY_REQUESTS`를 일반 호출 장애(502)나 수취인 없음(404)으로 바꾸지 않도록 처리해야 합니다. 회원 없음도 `404 + USER_RECIPIENT_NOT_FOUND`가 함께 확인될 때만 판단합니다. Payment 등 호출 서비스의 반영은 해당 담당자와 별도로 맞춥니다.

## 세션·보안 정책

| 항목 | 값 |
|---|---|
| 세션 쿠키 | `SESSION`, HttpOnly, SameSite=Lax, 로컬 Secure 끔·배포 Secure 켬 |
| 유휴 만료 | 마지막 사용 후 60분 (`spring.session.timeout`) |
| 절대 만료 | 로그인 후 24시간, 사용 중이어도 다시 로그인 (`travelpay.user.session.absolute-timeout`) |
| 세션 저장소 | 세션 Redis (`redis-session`, 메모리가 차도 키를 지우지 않는 `noeviction`) |
| 응답 캐시 | Spring Security가 `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`를 붙여 인증·개인정보 응답이 저장되지 않게 함 |
| 회원 상태 확인 | 로그인이 필요한 요청마다 DB에서 현재 상태·권한 확인. 정지·종료 회원은 로그인이 필요한 API 중 로그아웃만 가능 |

### 로그인 시도 제한

비밀번호 추측을 막기 위해 로그인 실패를 두 단위로 셉니다. 설정은 `travelpay.user.login-attempt`입니다.

| 단위 | 한도 | 결과 |
|---|---|---|
| 같은 이메일 + 같은 IP | 첫 실패부터 10분 안에 5번 실패 | 그 이메일·IP 조합을 20분 동안 `429 COMMON_TOO_MANY_REQUESTS` |
| 같은 이메일 (IP 무관) | 첫 실패부터 10분 안에 20번 실패 | 그 이메일을 모든 IP에서 20분 동안 429 |

- 실패로 세는 것: 비밀번호 불일치, 없는 이메일. 두 경우 모두 같은 `401 USER_INVALID_CREDENTIALS`라서 이메일 존재 여부를 알 수 없습니다.
- 세지 않는 것: 입력 형식 오류(400), 올바른 비밀번호를 입력한 정지·종료 회원(403), 서버·DB·Redis 장애(503).
- 로그인에 성공하면 그 이메일+IP 실패 횟수는 0이 됩니다. 이메일 전체 횟수는 유지해 여러 IP에 나눈 공격을 계속 셉니다.
- 막힌 동안에는 비밀번호를 확인하지 않고 바로 429를 반환합니다.
- 요청을 동시에 여러 개 보내면, 단위마다 실패 횟수와 아직 끝나지 않은 시도의 합이 한도에 닿은 뒤의 요청은 바로 429입니다. 끝나지 않은 시도는 실패로 확정될 때만 실패 횟수에 더해집니다.
- 다른 IP의 공격으로 실제 회원이 바로 잠기지 않도록 엄격한 차단은 이메일+IP 단위로 하고, 이메일 단위는 더 높은 한도를 둡니다.
- 횟수는 세션 Redis에 저장하며, 키에는 이메일·IP 원문 대신 SHA-256 해시를 사용합니다. Redis를 사용할 수 없으면 제한 없이 진행하지 않고 503을 반환합니다.

남은 위험:

- 공격자가 여러 IP로 같은 이메일을 20번 실패시키면 실제 회원도 20분 동안 로그인할 수 없습니다. 여러 IP에 나눈 추측을 막기 위해 받아들인 부분입니다.
- IP를 쉽게 바꿀 수 있는 공격자(예: IPv6 대역 사용)는 이메일+IP 제한을 피할 수 있고, 이때는 이메일 단위 제한만 적용됩니다.

## 오류 응답

| 상태 | 코드 | 상황 |
|---|---|---|
| 400 | `COMMON_INVALID_REQUEST` | 입력 형식 오류. 필드별 안내는 `error.details` |
| 400 | `USER_TERMS_CONSENT_INVALID` | 필수 약관 누락, 없는 약관·버전 |
| 401 | `COMMON_UNAUTHORIZED` | 세션 없음·만료(유휴 60분·절대 24시간), 회원 정보 없음 |
| 401 | `USER_INVALID_CREDENTIALS` | 이메일 없음 또는 비밀번호 불일치 |
| 403 | `COMMON_CSRF_INVALID` | CSRF 토큰 없음·불일치 |
| 403 | `COMMON_FORBIDDEN` | 권한 부족, I03에서 요청자와 조회 대상 불일치 |
| 403 | `USER_ACCOUNT_SUSPENDED`, `USER_ACCOUNT_CLOSED` | 정지·종료 회원. 올바른 비밀번호로 로그인하거나 기존 세션으로 요청할 때, 충전 예금주 조회(I03) |
| 404 | `USER_RECIPIENT_NOT_FOUND`, `USER_NOT_FOUND` | 내부 이름 조회 대상 없음(I02·I03) |
| 409 | `USER_EMAIL_ALREADY_EXISTS`, `USER_PHONE_ALREADY_EXISTS` | 이미 가입한 이메일·휴대폰 번호 |
| 409 | `COMMON_CONFLICT` | 같은 세션의 다른 요청이 로그인·로그아웃으로 세션을 바꾼 경우 |
| 429 | `COMMON_TOO_MANY_REQUESTS` | 로그인 시도 제한 또는 I02 요청자별 조회 한도 초과 |
| 503 | `COMMON_SERVICE_UNAVAILABLE` | DB·Redis 장애, 시행 중인 약관 없음 |

503은 장애를 뜻하므로 기존 세션 쿠키를 지우지 않습니다. 잠시 후 같은 쿠키로 다시 시도할 수 있습니다.

## DB 스키마 (Flyway)

테이블과 초기 데이터는 `user-service/src/main/resources/db/migration`의 `V번호__설명.sql` 파일로 관리합니다. 앱이 시작할 때 Flyway가 아직 적용하지 않은 파일만 번호 순서대로 실행하고 실행 기록을 `user_service.flyway_schema_history`에 남깁니다. Hibernate는 표를 만들거나 바꾸지 않고 엔티티와 맞는지 검사만 합니다(`ddl-auto: validate`).

| 파일 | 내용 |
|---|---|
| `V1__create_user_tables.sql` | 회원·로그인 수단·약관·약관 동의 표 |
| `V2__seed_initial_terms.sql` | 초기 약관 `SERVICE_TERMS`·`PRIVACY_TERMS` |

- 이미 적용한 파일은 고치지 않습니다. 고치면 앱이 시작할 때 검증에서 멈춥니다. 구조나 데이터를 바꿀 때는 다음 번호의 새 파일을 추가합니다.
- 엔티티를 바꾸면 같은 작업에서 마이그레이션 파일도 추가합니다. `FlywayMigrationTest`가 마이그레이션 결과를 엔티티 기준 스키마와 비교해 다르면 실패합니다.
- 제약조건 이름은 고유키·외래키는 엔티티에 적은 이름(`uk_`·`fk_`)을, 기본키·CHECK는 PostgreSQL 기본 이름(`<표>_pkey`, `<표>_<열>_check`)을 씁니다. Hibernate가 기본키·CHECK에는 이름을 붙이지 못해 DB 테스트용 스키마에 기본 이름이 붙고, `FlywayMigrationTest`가 이름까지 비교하기 때문입니다.
- 배포 DB(RDS 등)는 로컬의 `infra/postgres/init/01-service-schemas.sql`과 같은 준비를 관리자가 먼저 합니다. 서비스 계정, `user_service` 스키마와 소유권, 접속 권한, 계정의 `search_path`입니다. 서비스 계정은 DB 접속 권한만 있어서 Flyway가 스키마를 만들 수 없습니다.
- 앱의 DB 연결은 계정의 `search_path`나 `USER_DB_URL`의 `currentSchema=user_service`로 `user_service`를 가리켜야 합니다. `spring.flyway.default-schema`는 Flyway에만 적용됩니다.

## 테스트

```bash
./gradlew :user-service:test
```

테스트는 Testcontainers로 PostgreSQL·Redis 컨테이너를 띄우므로 Docker가 실행 중이어야 합니다.

## Gateway 연결과 배포 시 신뢰 경계

- Gateway는 연결 상대와 신뢰 프록시 설정으로 Client-Ip를 작성합니다. User의 Tomcat은 신뢰하는 내부 연결에서 온 Client-Ip를 `request.getRemoteAddr()`에 반영하고, 로그인 제한은 이 값을 사용합니다. 실제 배포에서는 ALB 서브넷 설정과 User 앞단의 신뢰 프록시 범위를 확인해야 합니다.
- Gateway는 외부 User-Id·User-Role을 지운 뒤 I01의 검증 결과로 다시 설정합니다. User 자신의 보호 API는 자기 세션을 확인하고(`travelpay.auth.header-context.enabled: false`), 내부 I02·I03은 컨트롤러에서 전달된 회원 헤더를 직접 검사합니다.
- `/internal/**`는 외부 Gateway 라우트에 없습니다. 배포에서는 User 포트를 외부에 열지 않고 허용된 내부 호출자만 접근시켜야 합니다. 회원 헤더 형식·본인 확인·횟수 제한은 호출 서비스 인증을 대신하지 않습니다. 외부에서 직접 접근할 수 있으면 헤더를 위조할 수 있으므로 내부망 격리가 필요합니다.
