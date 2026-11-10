# common 공통 모듈 사용 가이드

`common`은 실행 서버가 아닌 공유 Java 라이브러리입니다. 공통 응답·오류, MVC 요청자 정보 전달, Feign 실패 분류·내부 헤더 전달, 페이징을 제공합니다. 로그인 세션 발급·검증은 User, 외부 요청의 신뢰 경계는 Gateway가 담당합니다.

## 모듈 적용

각 서비스는 기존 멀티 모듈 구성에서 다음 의존성을 사용합니다.

```groovy
implementation project(':common')
```

오류 계약은 Spring 타입 없이 HTTP 상태를 `int`로 표현합니다. MVC·Feign·페이징 기능에 필요한 Spring·Feign·Jackson API는 common에서 `compileOnly`로 선언하고, 실행 앱이 필요한 starter를 직접 선택합니다. common 패키지를 앱의 컴포넌트 스캔에 추가할 필요는 없습니다.

## 구성 선택과 이유

- **공통 형식은 한 모듈에서 관리합니다.** 응답·오류·헤더 규격이 서비스마다 달라지는 것을 줄이되, 이메일 중복·잔액 부족 같은 업무 오류는 소유 서비스에 둡니다.
- **웹 환경별 처리는 조건부로 등록합니다.** MVC 처리기를 각 앱에 복사하지 않고 자동 설정으로 제공하며, WebFlux Gateway는 자기 처리기에서 같은 오류 형식을 사용합니다.
- **회원 정보 전달과 인증을 구분합니다.** 업무 서비스는 검증된 회원 헤더를 전달받고 User는 자기 세션을 확인합니다. 공통 Provider·Resolver는 이 차이를 유지하면서 컨트롤러에 같은 타입을 전달합니다.

## 공통 응답·오류

JSON 본문이 있는 업무 API는 `success`·`data`·`error` 구조를 사용합니다. 성공하면 `data`에 결과를 담고 `error`는 null, 실패하면 `data`는 null이고 `error`에 오류 정보를 담습니다. 기존 HTTP 상태 코드는 유지합니다.

성공 응답은 컨트롤러에서 `ApiResponse.success(responseDto)`로 반환합니다. 다음은 User 회원가입(`POST /api/v1/users`) 성공 응답의 예시입니다.

```json
{
  "success": true,
  "data": {
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "status": "ACTIVE",
    "loginRequired": true
  },
  "error": null
}
```

성공 안내 문구는 프런트엔드에서 정하므로 공통 성공 메시지 필드를 두지 않습니다. 실패 원인 안내는 `error.message`로 제공합니다. 충전처럼 접수와 완료를 구분해야 하는 기능은 응답 DTO에 처리 상태를 담습니다.

MVC 오류는 common의 처리기가 `ApiResponse.failure(errorResponse)`로 반환합니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "USER_EMAIL_ALREADY_EXISTS",
    "message": "이미 사용 중인 이메일입니다.",
    "details": []
  }
}
```

`details`는 필드별 상세 안내 목록입니다. 각 항목은 `field`와 `message`를 담으며, 상세 안내가 없으면 빈 배열 `[]`을 반환합니다. 이전 `errors` 필드는 `details`로 변경했습니다.

Gateway 자체 오류도 `ApiResponse.failure(errorResponse)`로 반환합니다. 서비스에서 받은 응답은 다시 감싸지 않고 전달합니다. `204`·`HEAD` 응답에는 JSON 본문을 넣지 않습니다.

## 공통 파일의 역할

| 파일 | 역할 |
|---|---|
| ErrorCode | 오류 코드·안내 메시지·HTTP 상태의 공통 규격 |
| CommonErrorCode | 입력 형식·인증·권한·HTTP 처리·서버 장애의 공통 오류 |
| BusinessException | ErrorCode를 담아 전달하는 업무 예외 |
| ApiResponse | success·data·error로 구성한 공통 응답 |
| ErrorResponse | code·message·details로 구성한 오류 정보 |
| AuthHeaders | User-Id·User-Role·Request-Id 헤더 이름 |
| AuthenticatedUser·AuthRole | 현재 요청자의 내부 회원 UUID와 역할(`USER`·`MASTER`) |
| CurrentUserProvider·CurrentUserArgumentResolver | 현재 요청자를 조회하고 `@CurrentUser` 매개변수에 전달 |
| FeignErrors | Feign 호출 실패의 오류 코드 읽기·거절 확인·공통 오류 변환 |
| InternalHeaderRelayInterceptor | 사용자 요청 중의 Feign 호출에 User-Id·User-Role·Request-Id 전달 |
| PageResponse·PageableUtils | 페이지 응답과 요청 크기·정렬 검증 |

`AuthHeaders`에는 Gateway·User가 사용하는 `Original-Method`·`Csrf-Token`·`Client-Ip` 이름도 정의되어 있습니다. 헤더 이름을 공유하는 것과 그 값을 신뢰할 수 있는지는 별도의 문제입니다.

## 서비스별 업무 오류

이메일 중복·잔액 부족 같은 업무 오류 enum은 소유 서비스에 두고 `ErrorCode`를 구현합니다.

```java
public enum UserErrorCode implements ErrorCode {
    EMAIL_ALREADY_EXISTS;

    public String getCode() {
        return "USER_EMAIL_ALREADY_EXISTS";
    }

    public String getMessage() {
        return "이미 사용 중인 이메일입니다.";
    }

    public int getStatus() {
        return 409;
    }
}
```

업무 실패는 `throw new BusinessException(UserErrorCode.EMAIL_ALREADY_EXISTS);`처럼 전달합니다. 위 enum은 사용 예시이며, 실제 코드는 user-service의 `UserErrorCode`입니다. 새 응답 규격에서는 `error.code` 문자열을 계약으로 사용합니다.

## 처리 위치

| 발생 위치 | 처리기 |
|---|---|
| 업무 서비스 5개·Mock Bank의 MVC 요청 | common의 error/GlobalExceptionHandler를 조건부 자동 등록 |
| Gateway의 WebFlux 요청 | gateway의 error/GatewayErrorHandler에서 공통 ApiResponse 실패 응답 작성 |
| User의 로그인·인가·CSRF 보안 필터 | user-service의 `RestAuthenticationEntryPoint`(인증 실패 401·회원 확인 장애 503)와 `RestAccessDeniedHandler`(CSRF·권한·회원 상태 403)가 공통 실패 응답 작성 |
| User의 세션 저장소(Redis) 오류 | `RedisSessionErrorResponseFilter`가 기본 503·동시 세션 변경 409를 작성. 비밀번호 DB 변경 완료 뒤의 세션 갱신 실패는 User 전용 503으로 구분 |

MVC 처리기는 `ResponseEntityExceptionHandler`를 확장해 요청 검증·JSON 파싱·메소드·미디어 타입 오류의 상태를 유지합니다. 프레임워크의 `Allow` 같은 응답 헤더도 보존합니다. Gateway는 자신의 WebFlux 처리기에서 같은 오류 계약을 사용하며, 인증·세션 검증 호출은 Gateway 모듈이 담당합니다. User의 비밀번호 DB 변경 완료 뒤 세션 갱신이 실패하면 `503 USER_ACCOUNT_UPDATED_SESSION_UNAVAILABLE`로 알리고 새 비밀번호로 재로그인하도록 안내합니다.

## MVC 오류 처리기의 자동 등록

Boot가 common JAR의 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`를 읽어 `CommonWebAutoConfiguration`을 발견합니다. 앱 시작 시 아래 조건을 모두 만족할 때 기본 처리기를 등록합니다.

| 조건 | 의미 |
|---|---|
| ConditionalOnWebApplication(SERVLET) | Servlet 웹 앱에만 적용하고 WebFlux·비웹 앱은 제외 |
| ConditionalOnClass | Servlet·Spring MVC API가 있을 때만 적용 |
| ConditionalOnMissingBean(GlobalExceptionHandler.class) | 같은 타입이나 하위 타입의 처리기가 이미 있으면 기본 빈을 추가하지 않음 |

각 앱은 common 패키지를 직접 스캔할 필요가 없습니다. 자동 설정을 명시적으로 제외해야 한다면 해당 앱의 설정에 아래 값을 지정할 수 있습니다.

```yaml
spring:
  autoconfigure:
    exclude:
      - com.travelpay.common.config.CommonWebAutoConfiguration
```

이 설정은 공통 오류 처리기 등록을 제외하며 MVC 라이브러리 자체를 제거하지는 않습니다. 회원 정보 전달은 별도의 `CommonAuthWebAutoConfiguration`이 담당합니다. Feign 호출 지원은 [Feign 호출](#feign-호출)을 참고합니다.

요청 본문의 필드 검증 오류는 다음과 같이 필드명과 안내 메시지만 반환합니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "COMMON_INVALID_REQUEST",
    "message": "잘못된 요청입니다.",
    "details": [
      {
        "field": "email",
        "message": "이메일 형식을 확인해 주세요."
      }
    ]
  }
}
```

검증 안내 메시지는 입력 원문을 포함하지 않는 문구로 작성합니다. 타입 변환 등 모델 바인딩 오류에는 프레임워크의 상세 사유 대신 고정된 안내 문구를 사용하며, 거절된 입력값을 응답에 추가하지 않습니다. ResponseStatus 애너테이션이 지정한 HTTP 상태도 공통 응답으로 유지합니다. 예상하지 못한 예외는 상세 메시지를 숨기고 `COMMON_INTERNAL_SERVER_ERROR`와 500을 반환하며, 로그에는 예외 종류를 남깁니다.

공통 enum에 없는 프레임워크 오류 상태는 원래 HTTP 상태를 유지하고 `COMMON_HTTP_ERROR`와 일반 안내 메시지를 반환합니다. 업무 오류에는 이 대체 코드 대신 서비스가 정의한 코드를 사용합니다.

## 내부 헤더

`AuthHeaders.USER_ID`, `USER_ROLE`, `REQUEST_ID`를 사용해 헤더 이름을 공유합니다. Gateway는 클라이언트의 User-Id·User-Role을 제거하고 User의 세션 검증 결과로 다시 작성합니다. Request-Id도 Gateway가 새로 생성하며, 사용자 신원을 증명하는 값으로 사용하지 않습니다.

common은 회원 ID·역할을 담는 `AuthenticatedUser`와 현재 요청자를 읽는 `CurrentUserProvider`를 제공합니다. MVC에서는 인터셉터가 회원 헤더의 형식을 검사하고 현재 요청 속성에 저장합니다. Provider는 이 객체를 읽으며, Resolver는 컨트롤러의 `@CurrentUser AuthenticatedUser user` 파라미터에 전달합니다.

로그인·Redis 세션 검증·호출 서비스 인증은 이 코드가 수행하지 않습니다. mTLS·별도 서비스 호출 인증은 MVP 이후 검토합니다.

## MVC 회원 정보 전달 활성화

Wallet·Payment·Exchange·Ledger의 로컬·배포 YAML에 다음 설정을 둡니다. User는 Redis 세션으로 회원을 직접 확인하므로 `false`로 두어 요청 헤더로 회원 정보를 만들지 않습니다.

```yaml
travelpay:
  auth:
    header-context:
      enabled: true
```

common을 의존성에 추가하는 것만으로 헤더 저장 처리가 켜지지는 않습니다. 기본값이 비활성이므로 각 앱의 YAML에서 사용을 선택합니다. 두 환경의 설정 파일을 독립적으로 사용하므로 같은 값을 각각 작성합니다.

설정을 켜면 `CurrentUserHeaderInterceptor`를 MVC 요청 처리 과정에 등록합니다. 설정을 생략하거나 false로 두어도 Provider·Resolver는 등록되지만, 공통 인터셉터는 헤더로 회원 객체를 준비하지 않습니다. 회원 정보가 없는 상태에서 필수 조회를 하면 401을 반환합니다.

두 회원 헤더가 모두 없으면 인터셉터는 요청을 통과시킵니다. 불완전·잘못된·중복 헤더는 401로 거절하고, 서버 코드가 이미 저장한 회원 객체는 덮어쓰지 않습니다. `@CurrentUser`를 다른 입력 바인딩 애너테이션과 함께 붙이지 않습니다.

Gateway·Eureka·Mock Bank에는 이 활성화 옵션을 추가하지 않습니다. 이는 로그인 기능을 켜는 설정이 아니라, 업무 서비스에서 공통 회원 정보 전달 코드를 사용하기 위한 설정입니다.

## Feign 호출

서비스끼리 Feign으로 호출할 때의 공통 규칙입니다. 응답 DTO·타임아웃·오류 코드 매핑은 호출하는 서비스가 정하고, 실패를 읽는 방법과 내부 헤더 전달은 common을 사용합니다.

| 항목 | 규칙 |
|---|---|
| 반환 타입 | 공통 `ApiResponse<응답 DTO>`. DTO에는 호출하는 쪽에 필요한 필드만 둡니다 |
| 실패 처리 | 어댑터에서 `FeignException`을 잡아 아는 오류 코드만 자기 서비스 오류로 바꾸고, 나머지는 `FeignErrors.toCommonException`으로 바꿉니다 |
| 재시도 | Spring Cloud OpenFeign 기본값은 자동 재시도 없음입니다. 별도 Retryer는 클라이언트 설정을 확인하고, 돈이 움직이는 재요청에는 같은 멱등 키를 사용합니다 |
| 타임아웃 | 클라이언트별로 각 서비스의 YAML에 둡니다 |

### 실패 처리

`FeignErrors`로 실패를 읽습니다.

| 메서드 | 하는 일 |
|---|---|
| `errorCode(e)` | 실패 응답의 `error.code`. 본문이 없거나 공통 형식이 아니면 빈 값 |
| `hasErrorCode(e, code)` | 그 오류 코드인지 확인 |
| `isRejected(e)` | HTTP 상태가 4xx인지 분류. 그 오류가 뜻하는 거절 범위는 상대 API 계약으로 확인 |
| `toCommonException(e)` | 따로 처리하지 않은 실패를 공통 오류로 바꿉니다 |

시간 초과·503·연결 실패는 상대가 처리했는지 모르는 결과일 수 있습니다. HTTP 상태만으로 DB 롤백을 보장할 수 없으므로, 돈이 움직이는 호출은 그 실패만으로 반대 거래를 실행하지 않고 같은 멱등 키로 재요청하거나 결과를 조회합니다. 상대 계약상 거절이 확인되어도 그 한 번의 요청에 대한 판단입니다. 같은 키로 먼저 보낸 요청의 결과를 몰랐다면 나중의 거절만으로 한 번도 적용되지 않았다고 판단하지 않습니다.

`toCommonException`의 변환 규칙입니다. 개별 서비스의 업무 오류 코드는 그대로 내보내지 않고, 알려진 공통 호출 제한 오류는 상태와 코드를 확인해 유지합니다.

| 실패 | 공통 오류 |
|---|---|
| HTTP 429와 `error.code = COMMON_TOO_MANY_REQUESTS`가 모두 일치 | 429 `COMMON_TOO_MANY_REQUESTS` |
| 시간 초과(연결·응답 대기), 상대의 504 | 504 `COMMON_GATEWAY_TIMEOUT` |
| 응답을 받지 못함(연결 거부·주소 없음·연결 끊김), 상대의 503 | 503 `COMMON_SERVICE_UNAVAILABLE` |
| 그 밖(처리하지 않은 4xx, 500·502, 응답 해석 실패, 요청 변환 실패) | 502 `COMMON_BAD_GATEWAY` |

HTTP 429라도 오류 코드가 다르거나 본문에서 코드를 읽을 수 없으면 기존 분류를 유지합니다. `Retry-After` 때문에 Feign 예외가 감싸져도 내부 응답의 코드를 읽어 같은 규칙을 적용합니다. 이 함수는 오류 분류를 담당하며, 자동 재시도와 `Retry-After` 응답 헤더 전달은 별도 정책입니다.

회원 없음처럼 업무 의미가 있는 실패는 호출 서비스가 **HTTP 상태와 오류 코드**를 함께 확인합니다. 다음은 송금 수취인 실명 조회 어댑터의 실패 분기 예시입니다.

```java
catch (FeignException exception) {
    if (exception.status() == 404
            && FeignErrors.hasErrorCode(exception, "USER_RECIPIENT_NOT_FOUND")) {
        return Optional.empty();
    }
    throw FeignErrors.toCommonException(exception);
}
```

429는 위 공통 변환이 처리하므로 호출부에 동일한 분기를 반복해서 추가할 필요가 없습니다. 회원 조회의 업무 오류는 해당 API가 정한 HTTP 상태와 오류 코드의 의미를 함께 확인합니다.

### 내부 헤더 전달

`CommonFeignAutoConfiguration`은 Feign을 쓰는 MVC 서비스에 `InternalHeaderRelayInterceptor`를 등록합니다. 이 인터셉터는 그 서비스의 모든 Feign 클라이언트에 적용되며, 사용자 요청을 처리하는 중에 하는 호출에 다음 헤더를 붙입니다.

| 헤더 | 값 |
|---|---|
| `User-Id`·`User-Role` | `CurrentUserProvider`가 검증한 현재 요청자. 들어온 헤더를 다시 읽지 않습니다 |
| `Request-Id` | 들어온 요청의 `Request-Id` |

- 클라이언트 메서드가 직접 지정한 헤더는 덮어쓰지 않습니다. `User-Id`·`User-Role`은 한 쌍이라 하나라도 지정하면 둘 다 그대로 둡니다.
- Kafka 소비자·스케줄러처럼 들어온 요청이 없는 호출에는 붙지 않습니다. 이런 호출에서 요청자가 필요하면 `@RequestHeader(AuthHeaders.USER_ID)`·`@RequestHeader(AuthHeaders.USER_ROLE)`로 직접 보냅니다.
- `User-Id`·`User-Role`은 요청자 정보이고, 대상 회원은 경로·파라미터로 따로 받습니다. 각 API는 계약에 따라 요청자와 대상의 소유권·권한 관계를 검사합니다. 예를 들어 수취인 지갑을 조회해도 헤더에는 송금인 정보가 붙고, 충전 예금주(본인) 조회에서는 요청자와 대상 ID가 같아야 합니다.
- 요청자가 반드시 필요한 내부 API는 헤더가 없으면 거절합니다. 그래야 호출 위치가 바뀌어 헤더가 빠졌을 때 바로 드러납니다.
- Gateway를 통과한 요청은 Gateway가 생성한 `Request-Id`를 전달받습니다. 인터셉터 자체는 ID를 생성하지 않으므로 값이 없는 직접 호출에는 추가하지 않습니다.

서비스가 `InternalHeaderRelayInterceptor` 빈을 직접 등록하면 기본 빈을 추가하지 않습니다. 자동 등록을 제외하려면 `spring.autoconfigure.exclude`에 `com.travelpay.common.config.CommonFeignAutoConfiguration`을 지정합니다.

## 페이징

페이지 요청은 `PageableUtils.toStablePageable`로 크기와 정렬을 확인할 수 있습니다. 아래 `pageable`은 요청에서 받은 Pageable이며, 허용 필드와 최대 100건은 사용 예시입니다. 각 API가 실제 조회 필드와 정책을 지정합니다.

```java
var normalized = PageableUtils.toStablePageable(
        pageable, Set.of("createdAt", "id"), "id", 100);
```

- 페이지 크기는 지정한 최댓값으로 제한하고, 허용하지 않은 정렬 필드와 unpaged 요청은 400으로 거절합니다.
- 동률의 순서를 정하기 위해 서버가 지정한 고유 필드를 마지막 정렬 기준에 추가합니다. 실제 조회 대상의 NULL 없는 고유 속성을 지정해야 하며, 클라이언트 허용 정렬 목록과는 별개입니다.
- 정렬이 안정적이어도 페이지 조회 사이의 데이터 추가·삭제까지 방지하지는 않습니다.
- 조회 결과 `Page<T>`는 `PageResponse.from(page)`로 변환합니다. DTO 변환이 필요하면 `PageResponse.from(page, mapper)`를 사용합니다.

`PageResponse`는 `items`·`page`·`size`·`totalElements`·`totalPages`를 담습니다. page는 0부터 시작하며, 컨트롤러는 필요에 따라 `ApiResponse.success(pageResponse)`로 감쌉니다.

## 검증

```bash
./gradlew :common:test
```

common 테스트는 응답·오류 계약, 요청자 헤더 파싱과 Provider·Resolver 연결, 조건부 자동 등록, Feign 오류·헤더 전달, 페이징을 확인합니다. 각 앱의 인증·DB·세션 연동은 해당 서비스 테스트에서 확인하며, 서비스 사용법의 확인 기준은 그 서비스 가이드를 따릅니다.

근거: [Spring MVC 예외 처리 확장](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/ResponseEntityExceptionHandler.html), [Spring Boot의 Jackson 3 지원](https://docs.spring.io/spring-boot/reference/features/json.html), [Spring Cloud OpenFeign 기본 설정](https://docs.spring.io/spring-cloud-openfeign/reference/spring-cloud-openfeign.html)
