# Gateway 실행·프런트 연결·배포 가이드

Gateway는 브라우저 요청을 받아 User에 세션 검증을 요청하고, 검증된 회원 정보로 업무 서비스에 전달합니다. 브라우저는 User·Wallet 포트에 직접 접속하는 대신 Gateway 주소를 API 기준 주소로 사용합니다.

## 구성 선택과 이유

- **세션 검증은 User에 요청합니다.** Gateway가 세션 Redis 구조를 공유하지 않고 User의 세션 확인 API로 현재 회원 상태와 역할을 확인합니다. User 자신의 보호 API는 실제 요청에서도 SecurityContext로 인증하므로, Gateway의 회원 헤더를 로그인 근거로 사용하지 않습니다.
- **외부 회원 헤더는 검증한 값으로 교체합니다.** User-Id·User-Role 위조를 막기 위한 경계입니다. Request-Id는 새로 생성하고 Client-Ip는 신뢰하는 연결의 값으로 판단합니다. 서비스 포트의 외부 접근 제한은 배포 망에서 적용합니다.
- **추적 정보는 관측 시작 전부터 제한합니다.** 필터가 헤더를 지울 때는 부모 trace가 이미 추출됐을 수 있어, 외부 trace 추출 설정과 전달 헤더를 함께 제한합니다. 내부 호출은 새 traceId를 이어 씁니다.
- **CORS와 HTTP 오류는 프레임워크 확장점에서 처리합니다.** 별도 OPTIONS 허용 필터를 만들지 않고, 정상 preflight와 실제 API의 인증·CSRF 검사를 구분합니다. 오류 응답에서는 no-store와 Allow 같은 필요한 HTTP 헤더를 유지합니다.
- **세션 확인 대기 시간과 업무 호출 시간을 구분합니다.** 연결·읽기·전체 제한은 세션 검증 구간에만 적용해, 검증 후 업무 API가 늦어진 것을 인증 장애로 분류하지 않습니다.

## 파일의 책임

| 파일 | 책임 |
|---|---|
| `NormalizedPathFilter` | 우회 해석될 경로 거절 |
| `InternalHeaderResetFilter` | 외부 내부용·추적 헤더 정리, Request-Id·Client-Ip 작성 |
| `ClientIpResolver`·`ClientIpProperties` | 신뢰 연결 대역과 IP 형식 확인 |
| `PublicEndpoints`·`SessionAuthenticationFilter` | 공개 요청 분류와 세션 확인 결과에 따른 전달·거절 |
| `SessionVerificationClient` | 필요한 헤더만 전달하고 검증 응답을 엄격히 해석 |
| `SessionVerificationConfiguration`·`SessionVerificationProperties` | 내부 HTTP 클라이언트와 관측·제한 시간 설정 |
| `GatewayErrorHandler` | 공통 JSON·HTTP 상태·Allow·no-store 처리 |
| `GatewayCorsConfiguration` | 허용 출처 검증과 프레임워크 CORS 거절 응답 연결 |

## 주소와 포트

| 용도 | 로컬 기본값 | 배포 기준 |
|---|---|---|
| 프런트 | `http://localhost:<확정할 포트>` | `https://travelpay.store`, `https://www.travelpay.store` |
| API | `http://localhost:8080` | `https://api.travelpay.store` |
| Gateway 관리 | `0.0.0.0:18080`에서 수신, PC에서는 `localhost:18080`로 접근 | 내부망의 `18080` |

프런트의 Vercel 배포 여부와 백엔드의 EC2·ECS 선택은 API 코드와 분리합니다. 현재 실제 DNS·인증서·AWS 리소스는 연결하지 않았습니다.

## 로컬 실행

1. `.env.example`을 개인 `.env`로 복사하고 DB·Redis 값을 준비합니다. Compose는 `.env`를 읽고, Spring Boot에는 실행 환경변수로 전달합니다.
2. `docker compose up -d postgres redis-session`으로 User 기반을 실행합니다.
3. 각각의 터미널 또는 IntelliJ 실행 설정에서 Eureka → User → 필요한 업무 앱 → Gateway 순서로 실행합니다.

환경 변수는 실행할 앱마다 전달합니다. IntelliJ에서는 각 실행 설정의 환경 파일로 프로젝트 루트의 `.env`를 선택합니다. 로컬 설정을 명시할 때는 실행 인자로 `--spring.config.name=application --spring.profiles.active=default`를 사용합니다. 터미널에서는 아래처럼 환경을 불러온 뒤 각각의 앱을 실행합니다.

```bash
set -a
source .env
set +a

./gradlew :eureka-server:bootRun
./gradlew :user-service:bootRun
```

Gateway는 프런트 포트가 정해지면 다음처럼 실행합니다. `5173`은 예시이므로 실제 포트로 바꿉니다.

```bash
GATEWAY_CORS_ALLOWED_ORIGINS=http://localhost:5173 ./gradlew :gateway:bootRun
```

기본 Eureka 주소는 `http://localhost:8761/eureka/`입니다. 업무 앱이 아직 실행되지 않았다면 해당 앱으로 가는 보호 요청은 정상 완료되지 않습니다. User 자체 API와 CSRF·로그인 흐름부터 확인할 수 있습니다.

## 세션 검증과 라우팅

```text
브라우저 → Gateway: SESSION 쿠키가 있는 API 요청
  → 외부 회원·요청 식별·IP·추적 헤더 정리
  → User: POST /internal/v1/auth/session
      SESSION + 실제 Original-Method + 필요한 Csrf-Token 전달
  → User: Redis 세션·만료·DB의 현재 회원 상태와 역할 확인
  → Gateway: 검증 결과로 User-Id·User-Role 설정
  → 업무 서비스: 회원 헤더로 이번 요청의 AuthenticatedUser 준비
  → 업무 서비스: 소유권·권한 확인 후 응답
```

현재 Gateway는 공개 목록을 제외한 매핑 요청을 User의 세션 확인 API로 먼저 확인합니다. User 자신의 보호 API도 Gateway를 거치면 이 확인을 받고, 실제 User API에서는 세션의 SecurityContext를 근거로 요청자를 판단합니다. User의 `header-context.enabled`는 `false`입니다.

User 보호 API의 중복 세션 확인 호출을 줄이는 구조는 후속 검토 사항이며 현재 동작에 반영되지 않았습니다. User의 관리자 API(`/api/v1/admin/users/**`·`/api/v1/admin/terms/**`)는 User 라우트로 SESSION 쿠키와 함께 전달되며, User의 `/api/v1/admin/**` 규칙이 ACTIVE MASTER만 통과시킵니다.

| 라우트 | 전달 대상 | SESSION 쿠키 |
|---|---|---|
| `/api/v1/auth/**`, `/api/v1/terms/**`, `/api/v1/users/**`, `/api/v1/admin/users/**`, `/api/v1/admin/terms/**` | User | 전달 |
| `/api/v1/wallets/**`, `/api/v1/charges/**` | Wallet | 제거 |
| `/api/v1/payments/**`, `/api/v1/transfers/**` | Payment | 제거 |
| 환율·한도·견적·환전 경로 | Exchange | 제거 |

환전 경로는 `/api/v1/exchange-rates/**`, `/api/v1/exchange-limits/**`, `/api/v1/exchange-quotes/**`, `/api/v1/exchanges/**`입니다. 정확한 매핑은 실행 시 사용하는 Gateway 설정의 `routes` 목록을 기준으로 합니다. `/internal/**`, `/actuator/**`는 외부 API 라우트에 없습니다.

로그인 없이 통과하는 조합은 다음과 같습니다. 공개 API라도 회원가입·로그인·로그아웃의 CSRF 및 세션 확인은 User가 담당합니다.

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/api/v1/auth/csrf` | CSRF 토큰 조회 |
| GET | `/api/v1/terms` | 약관 조회 |
| POST | `/api/v1/users` | 회원가입 |
| POST | `/api/v1/auth/login` | 로그인 |
| POST | `/api/v1/auth/logout` | 정지 회원도 자기 세션 종료 가능 |
| GET | `/api/v1/exchange-rates/**` | 공개 환율 조회 |

## 프런트 CORS와 쿠키

배포 기본 허용 출처는 `https://travelpay.store`, `https://www.travelpay.store`입니다. 로컬 기본 허용 목록은 비어 있으며, 확정한 프런트 포트를 `GATEWAY_CORS_ALLOWED_ORIGINS`로 지정합니다. 여러 주소는 쉼표로 구분합니다. 빈 값은 다른 출처를 허용하지 않는다는 뜻이므로, 배포에 빈 환경변수를 전달하면 YAML의 기본 두 주소도 사용하지 않습니다.

같은 팀 도메인의 HTTPS 프런트와 `https://api.travelpay.store`는 같은 사이트의 서로 다른 출처입니다. 현재 `SameSite=Lax` 정책으로 쿠키를 사용할 수 있지만, 프런트는 API 요청마다 `credentials: "include"`를 지정해야 합니다. SESSION은 API 호스트의 HttpOnly 쿠키이고, 프런트 코드가 쿠키 값을 직접 읽을 필요는 없습니다. [SameSite 설명](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie#samesitesamesite-value)

```javascript
const API_BASE = "https://api.travelpay.store";

const csrfResponse = await fetch(`${API_BASE}/api/v1/auth/csrf`, {
  credentials: "include",
});
const { data: csrf } = await csrfResponse.json();

const loginResponse = await fetch(`${API_BASE}/api/v1/auth/login`, {
  method: "POST",
  credentials: "include",
  headers: {
    "Content-Type": "application/json",
    [csrf.headerName]: csrf.token,
  },
  body: JSON.stringify(loginForm),
});

// 로그인 성공 후에는 같은 쿠키로 CSRF 토큰을 다시 조회해 이후 변경 요청에 사용합니다.
```

| 항목 | 정책 |
|---|---|
| 허용 출처 | 정확한 HTTPS 주소, 개발용 HTTP localhost 주소 |
| 요청 헤더 | Accept·Content-Type·Csrf-Token·Idempotency-Key |
| 자격증명 | 쿠키 포함 요청 허용 |
| 응답 헤더 | 서비스가 제공하는 Request-Id·Location·Retry-After를 프런트에 노출 가능 |
| 사전 요청 | 허용된 Origin·메서드·헤더를 검사한 정상 preflight만 200 |
| 일반 OPTIONS | 보호 경로라면 세션 검증 유지 |
| CORS 거절 | 403 공통 JSON·no-store, 서비스 호출 차단 |

정상 preflight의 200은 본문 없는 브라우저 통신 승인입니다. 실제 API 호출의 로그인·CSRF·권한 검사를 대신하지 않습니다. 허용되지 않은 Origin은 브라우저에서 JSON을 읽을 수 없어 CORS 오류로 보일 수 있습니다.

`*.vercel.app` 같은 와일드카드는 허용하지 않습니다. Vercel 미리보기 주소가 필요하면 별도 환경에 정확한 주소를 지정합니다. `vercel.app` 프런트와 다른 사이트의 API를 직접 연결하면 SameSite·브라우저의 타사 쿠키 제한을 함께 검토해야 합니다. 현재 배포 기준은 팀 도메인의 HTTPS 주소입니다.

## 시간 제한과 장애 응답

| 환경변수 | 기본값 | 제한 구간 |
|---|---:|---|
| `GATEWAY_SESSION_VERIFICATION_CONNECT_TIMEOUT` | 2s | User 접속 대기 |
| `GATEWAY_SESSION_VERIFICATION_RESPONSE_TIMEOUT` | 3s | 응답의 다음 데이터 대기 |
| `GATEWAY_SESSION_VERIFICATION_TOTAL_TIMEOUT` | 5s | 연결 전 대기부터 본문 해석 완료 |

세 값은 독립적입니다. 전체 상한을 넘으면 검증 대기를 취소하고 503으로 종료합니다. 검증 뒤의 업무 처리 시간은 이 상한에 포함하지 않습니다. 5초는 초기 기준값이며 운영 지연과 호출 실패율을 측정해 조정합니다. 값 변경은 재기동·재배포 때 적용됩니다.

User의 검증 거절 401·403·409는 상태와 본문을 전달하고, 연결 실패·시간 초과·해석할 수 없는 검증 응답 등은 503입니다. 두 오류 경로 모두 `Cache-Control: no-store`를 적용하고, 일시 장애만으로 SESSION 쿠키를 삭제하지 않습니다. 지원하지 않는 실제 메서드는 405와 Gateway의 `Allow` 목록으로 거절합니다. 업무 API 자체의 메서드·권한 판단은 해당 서비스가 맡습니다.

Gateway 수신 기본 한도는 8KiB이고, User는 로컬·배포 모두 16KiB로 두어 Gateway가 붙이는 내부 헤더의 여유를 확보했습니다. 테스트용 큰 쿠키는 검증 입력이며 실제 앱이 추가 쿠키를 발급하는 구조가 아닙니다.

`Connection`의 hop-by-hop 제거 목록에서도 내부 헤더 이름을 정리합니다. 그래야 Gateway가 새로 만든 헤더가 뒤의 전송 필터에서 다시 삭제되지 않습니다. 세션 확인 응답의 역할값도 엄격히 해석해 숫자가 enum 순서로 바뀌어 인증 결과로 받아들여지지 않게 합니다.

## 검증 범위

```bash
./gradlew :gateway:test
./gradlew :user-service:test --tests 'com.travelpay.user.presentation.controller.GatewayContractHttpTest'
```

Gateway HTTP 시험은 실제 Gateway 서버와 User·업무 서비스 대역 사이의 상태·본문·헤더·호출 여부를 확인합니다. User의 `GatewayContractHttpTest`는 실제 User 서버·PostgreSQL·Redis로 세션 확인·Client-Ip·쿠키 등 Gateway가 기대는 계약을 확인합니다. 이 시험은 Gateway 서버를 함께 실행하는 시험이 아닙니다. 전체 연결과 브라우저·배포 동작은 실제 실행 환경을 연결한 뒤 별도로 확인합니다.

## 내부 추적

Gateway는 외부 W3C·B3 부모 trace를 추출하지 않고 새 traceId를 시작합니다. 외부 baggage·추적 헤더도 제거하며, 내부 User 검증과 업무 호출에는 새 traceId를 W3C로 이어 보내고 각 호출의 spanId를 구분합니다. 헤더만 지우면 관측 코드가 이미 외부 부모를 읽었을 수 있어, 추출 설정과 전달 헤더를 함께 제한했습니다.

이 정책에서는 프런트가 보낸 traceId와 내부 traceId가 연결되지 않습니다. traceId는 인증·멱등성 키로 사용하지 않습니다. 다른 서비스의 내부 W3C 추출은 유지하므로 내부 호출의 연결은 계속됩니다.

## Cloudflare → ALB → Gateway 배포 시 연결할 것

현재 코드·설정의 검증과 실제 클라우드 배포는 구분합니다. 다음은 배포 때 적용할 기준입니다.

도메인 등록기관은 Gabia에 두고 DNS를 Cloudflare에서 관리한다면, Gabia의 네임서버를 Cloudflare가 안내한 값으로 위임합니다. 실제 DNS 관리 위치가 정해진 뒤 그곳에서 프런트·API 레코드를 설정합니다.

| 구간 | 설정 기준 |
|---|---|
| 프런트 DNS | Vercel에서 팀 도메인을 연결하고 프로젝트가 안내하는 A/CNAME 값을 사용 |
| API DNS | Cloudflare의 `api` 레코드를 ALB DNS 이름에 연결 |
| TLS | 프런트·API HTTPS, Cloudflare Full (strict), ALB에 API 호스트와 일치하는 인증서 |
| 공개 진입 | ALB 443은 Cloudflare에서, Gateway API 포트는 ALB에서 접근하도록 보안 그룹 제한 |
| IP 신뢰 | `GATEWAY_CLOUDFLARE_PROXY_ADDRESSES`에 실제 ALB 연결 서브넷 CIDR 지정 |
| 관리 포트 | 기본 18080, ALB 헬스체크·Prometheus가 접근하는 내부망으로 제한 |
| 업무 앱 | User·Wallet 등은 허용된 내부 호출자에서만 접근, 외부 포트 공개 금지 |
| API 캐시 | 인증·개인정보 응답의 no-store 유지, API 호스트에 강제 캐시 규칙 적용 금지 |

API 트래픽의 대상 포트는 Gateway 8080입니다. 헬스체크는 별도 관리 포트의 `GET /actuator/health`, Prometheus는 `/actuator/prometheus`를 사용합니다. 관리 포트는 API와 다른 양수 고정 포트로 지정하고 `0` 자동 할당을 사용하지 않습니다. 배포의 `0.0.0.0` 수신은 모든 인터페이스 수신이므로, 보안 그룹이 접근을 제한해야 합니다. [AWS 헬스체크](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/target-group-health-checks.html), [대상 보안 그룹](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/target-group-register-targets.html)

ECS의 `awsvpc`를 선택하면 태스크 IP를 대상으로 하는 `ip` 대상 그룹을 사용합니다. EC2의 bridge·동적 호스트 포트 방식을 선택하면 실제 매핑 포트에 맞춰 트래픽·헬스체크를 설정해야 합니다. 같은 숫자의 컨테이너 포트가 곧 호스트 포트라는 가정은 하지 않습니다. [ECS 네트워크](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-networking-awsvpc.html)

같은 `awsvpc` 태스크의 컨테이너는 IP와 포트 공간을 공유합니다. 8080을 사용하는 여러 실행 앱은 앱별 태스크 등 서로 다른 네트워크 공간에 배치해야 합니다.

현재 배포 YAML의 Eureka 주소와 인스턴스 이름은 Compose 서비스 이름 기준입니다. EC2를 나누거나 ECS로 배포할 때는 실제로 도달 가능한 Eureka 주소와 각 앱의 등록 주소를 외부 설정으로 제공해야 합니다. EC2·ECS 구성이 확정되기 전에는 주소를 추측해서 고정하지 않습니다.

배포 설정 선택은 기존 방식대로 `SPRING_CONFIG_NAME=application-prod`, `SPRING_PROFILES_ACTIVE=prod`를 사용합니다. 설정값을 외부에서 주입하므로 환경별 Java 코드 분기는 필요하지 않습니다.

참고: [Cloudflare Full (strict)](https://developers.cloudflare.com/ssl/origin-configuration/ssl-modes/full-strict/), [Vercel 팀 도메인 연결](https://vercel.com/docs/domains/working-with-domains/add-a-domain), [Gateway CORS](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/cors-configuration.html).
