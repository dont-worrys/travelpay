# 공통 응답·오류 사용 방법

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

`common`은 실행 애플리케이션이 아닌 단일 Java 라이브러리입니다. 오류 계약은 Spring 타입 없이 HTTP 상태를 `int`로 표현하고, MVC 오류 처리·자동 설정에 필요한 Spring API는 `compileOnly`로 선언합니다. 각 실행 앱이 필요한 starter를 직접 선택합니다.

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
| User의 세션 저장소(Redis) 오류 | user-service의 `RedisSessionErrorResponseFilter`가 503, 동시 세션 변경은 409 공통 실패 응답 작성 |

MVC 처리기는 `ResponseEntityExceptionHandler`를 확장해 요청 검증·JSON 파싱·메소드·미디어 타입 오류의 상태를 유지합니다. 프레임워크의 `Allow` 같은 응답 헤더도 보존합니다. Gateway의 공통 오류 응답 어댑터와 대응 테스트는 오류 계약을 연결하는 범위로 포함합니다. Gateway의 회원 인증·세션 검증 호출 기능은 포함하지 않습니다.

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

이 설정은 공통 오류 처리기 등록을 제외하며 MVC 라이브러리 자체를 제거하지는 않습니다. 회원 정보 전달은 별도의 `CommonAuthWebAutoConfiguration`이 담당합니다. Feign 호출 지원은 아직 추가하지 않았습니다.

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

`AuthHeaders.USER_ID`, `USER_ROLE`, `REQUEST_ID`를 사용해 헤더 이름을 공유합니다. Gateway 인증을 구현할 때 클라이언트의 User-Id·User-Role을 제거하고 검증한 값으로 다시 구성해야 합니다. Request-Id는 요청 추적용이며 사용자 신원을 증명하지 않습니다.

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

근거: [Spring MVC 예외 처리 확장](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/ResponseEntityExceptionHandler.html), [Spring Boot의 Jackson 3 지원](https://docs.spring.io/spring-boot/reference/features/json.html)
