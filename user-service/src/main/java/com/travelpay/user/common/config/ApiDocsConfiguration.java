package com.travelpay.user.common.config;

import java.util.EnumSet;
import java.util.Set;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.web.CurrentUser;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.csrf.CsrfToken;

/** 로컬 API 문서(Swagger)의 그룹과 로그인·CSRF 표시 규칙. 배포 설정에서는 springdoc이 꺼짐 */
@Configuration(proxyBeanMethods = false)
public class ApiDocsConfiguration {

    static final String SESSION_SCHEME = "session";
    static final String CSRF_SCHEME = "csrf";

    // 로그인 없이 쓰는 API. SecurityConfiguration의 permitAll과 같아야 함(테스트가 실제 보안 규칙과 비교)
    private static final Set<String> PUBLIC_OPERATIONS = Set.of(
            "GET /api/v1/auth/csrf", "GET /api/v1/terms", "POST /api/v1/users", "POST /api/v1/auth/login");

    // Spring Security가 CSRF를 확인하지 않는 메서드
    private static final Set<HttpMethod> METHODS_WITHOUT_CSRF =
            EnumSet.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.TRACE, HttpMethod.OPTIONS);

    static {
        // 요청자(@CurrentUser)와 CSRF 토큰 인자는 요청 값이 아니라 서버가 채우므로 입력칸에서 뺌
        SpringDocUtils.getConfig()
                .addAnnotationsToIgnore(CurrentUser.class)
                .addRequestWrapperToIgnore(CsrfToken.class);
    }

    @Bean
    GroupedOpenApi userApiDocs(@Value("${server.servlet.session.cookie.name}") String sessionCookieName) {
        return GroupedOpenApi.builder()
                .group("user")
                .displayName("회원·인증")
                .pathsToMatch("/api/v1/auth/**", "/api/v1/users/**", "/api/v1/terms/**")
                .addOpenApiCustomizer(openApi -> openApi.info(new Info()
                        .title("TravelPay 회원·인증 API")
                        .version("v1")
                        .description("""
                                회원가입, 로그인·로그아웃, 내 정보, 비밀번호 변경, 약관 조회 API입니다.
                                응답 본문은 공통 형식 `{success, data, error}`이고, 로그아웃·비밀번호 변경 성공은 본문 없는 204입니다.
                                Gateway를 거치지 않고 user-service를 직접 호출하며, 로그인은 세션 쿠키로 확인합니다. 쿠키는 브라우저가 자동으로 보냅니다.

                                사용 순서
                                1. `GET /api/v1/auth/csrf`를 실행하고 응답의 `token`을 Authorize의 csrf 칸에 넣습니다.
                                2. 회원가입·로그인을 실행합니다.
                                3. 로그인하면 이전 토큰이 버려지므로 `GET /api/v1/auth/csrf`로 새 토큰을 받아 다시 넣습니다. 비밀번호 변경·로그아웃 뒤에도 같습니다.

                                오류 코드와 자세한 규칙은 `docs/guide/user-service.md`를 봅니다.
                                """)))
                .addOpenApiCustomizer(loginAndCsrf(sessionCookieName))
                .build();
    }

    @Bean
    GroupedOpenApi userAdminApiDocs(@Value("${server.servlet.session.cookie.name}") String sessionCookieName) {
        return GroupedOpenApi.builder()
                .group("user-admin")
                .displayName("회원·약관 관리")
                .pathsToMatch("/api/v1/admin/users/**", "/api/v1/admin/terms/**")
                .addOpenApiCustomizer(openApi -> openApi.info(new Info()
                        .title("TravelPay 회원·약관 관리 API")
                        .version("v1")
                        .description("""
                                관리자(MASTER)의 회원 검색·정지·해제·세션 종료와 약관 버전 목록·새 버전 등록 API입니다.
                                로그인하지 않으면 401, ACTIVE 상태의 MASTER가 아니면 403입니다. MASTER 계정으로 로그인한 뒤 사용합니다.
                                로그인·CSRF 사용 순서는 회원·인증 그룹과 같습니다.
                                """)))
                .addOpenApiCustomizer(loginAndCsrf(sessionCookieName))
                .build();
    }

    // 각 API에 로그인(세션 쿠키)·CSRF 헤더가 필요한지 표시
    private static OpenApiCustomizer loginAndCsrf(String sessionCookieName) {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.components(new Components());
            }
            openApi.getComponents()
                    .addSecuritySchemes(SESSION_SCHEME, new SecurityScheme()
                            .type(SecurityScheme.Type.APIKEY)
                            .in(SecurityScheme.In.COOKIE)
                            .name(sessionCookieName)
                            .description("로그인 세션 쿠키. 브라우저가 자동으로 보내므로 입력하지 않습니다."))
                    .addSecuritySchemes(CSRF_SCHEME, new SecurityScheme()
                            .type(SecurityScheme.Type.APIKEY)
                            .in(SecurityScheme.In.HEADER)
                            .name(AuthHeaders.CSRF_TOKEN)
                            .description("`GET /api/v1/auth/csrf` 응답의 token. 로그인·비밀번호 변경·로그아웃 뒤에는 새로 받아 넣습니다."));
            openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                var requirement = new SecurityRequirement();
                if (!PUBLIC_OPERATIONS.contains(method + " " + path)) {
                    requirement.addList(SESSION_SCHEME);
                }
                if (!METHODS_WITHOUT_CSRF.contains(method)) {
                    requirement.addList(CSRF_SCHEME);
                }
                if (!requirement.isEmpty()) {
                    operation.addSecurityItem(requirement);
                }
            }));
        };
    }
}
