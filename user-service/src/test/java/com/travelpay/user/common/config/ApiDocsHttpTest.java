package com.travelpay.user.common.config;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.user.UserServiceApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 로컬 설정의 API 문서(Swagger)를 실제 HTTP 서버와 Redis 세션으로 확인합니다. */
@SpringBootTest(classes = UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                // 로그 형식은 테스트 JVM 전체에 남습니다. 다른 테스트가 일반 텍스트 로그를 확인하므로 JSON 로그를 켜지 않습니다.
                "logging.structured.format.console="
        })
class ApiDocsHttpTest {

    private static final String USER_DOCS = "/v3/api-docs/user";
    private static final String ADMIN_DOCS = "/v3/api-docs/user-admin";
    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withDatabaseName("travelpay_user_api_docs_test")
            .withUsername("travelpay_test")
            .withPassword(UUID.randomUUID().toString())
            .withInitScript("postgres/user-schema.sql")
            .withUrlParam("currentSchema", "user_service");

    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.2.11-bookworm"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD, "--appendonly", "no", "--save", "");

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    @Value("${local.server.port}") private int port;
    @Autowired private JsonMapper mapper;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void eachGroupDocumentsOnlyItsExternalApis() throws Exception {
        assertThat(docs(USER_DOCS).path("paths").propertyNames()).containsExactlyInAnyOrder(
                "/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/logout",
                "/api/v1/users", "/api/v1/users/me", "/api/v1/users/me/password", "/api/v1/terms");
        assertThat(docs(ADMIN_DOCS).path("paths").propertyNames()).containsExactlyInAnyOrder(
                "/api/v1/admin/users", "/api/v1/admin/users/{userId}/status",
                "/api/v1/admin/users/{userId}/sessions", "/api/v1/admin/terms");
    }

    @Test
    void theUngroupedDocumentThatWouldListInternalApisIsNotServed() throws Exception {
        assertEquals(404, get("/v3/api-docs").statusCode());
    }

    @Test
    void onlyInputsTheClientSendsAreDocumented() throws Exception {
        // 서버가 채우는 인자(@CurrentUser 요청자, CsrfToken)는 이름이 바뀌거나 펼쳐진 모양으로도 입력칸에 나오면 안 됩니다.
        var expected = Map.of(
                "GET /api/v1/admin/users", List.of("query userId", "query loginEmail", "query phone", "query status",
                        "query page", "query size"),
                "GET /api/v1/admin/terms", List.of("query termsCode", "query page", "query size"),
                "PATCH /api/v1/admin/users/{userId}/status", List.of("path userId"),
                "DELETE /api/v1/admin/users/{userId}/sessions", List.of("path userId"));
        documentedApis().forEach((api, operation) -> assertThat(operation.path("parameters").values())
                .as(api)
                .extracting(parameter -> parameter.path("in").asString() + " " + parameter.path("name").asString())
                .containsExactlyInAnyOrderElementsOf(expected.getOrDefault(api, List.of())));
    }

    @Test
    void adminSearchConditionsAreOptional() throws Exception {
        var apis = documentedApis();
        for (var api : List.of("GET /api/v1/admin/users", "GET /api/v1/admin/terms")) {
            for (var parameter : apis.get(api).path("parameters").values()) {
                assertFalse(parameter.path("required").asBoolean(), api + " " + parameter.path("name").asString());
            }
        }
    }

    @Test
    void everyApiHasATagAndSummaryInsteadOfClassNames() throws Exception {
        for (var operation : documentedApis().values()) {
            assertThat(operation.path("summary").asString()).isNotBlank();
            assertThat(operation.path("tags").values()).singleElement()
                    .satisfies(tag -> assertThat(tag.asString()).doesNotEndWith("-controller"));
        }
    }

    @Test
    void requestAndResponseBodiesAreDocumentedAsJson() throws Exception {
        for (var operation : documentedApis().values()) {
            assertThat(operation.path("requestBody").path("content").propertyNames())
                    .isSubsetOf("application/json");
            for (var response : operation.path("responses").values()) {
                assertThat(response.path("content").propertyNames()).isSubsetOf("application/json");
            }
        }
    }

    @Test
    void loginIsDocumentedAsTheSessionCookieAndCsrfAsItsHeader() throws Exception {
        for (var docs : List.of(USER_DOCS, ADMIN_DOCS)) {
            var schemes = docs(docs).path("components").path("securitySchemes");
            var session = schemes.path(ApiDocsConfiguration.SESSION_SCHEME);
            var csrf = schemes.path(ApiDocsConfiguration.CSRF_SCHEME);
            assertEquals("cookie", session.path("in").asString());
            assertEquals("SESSION", session.path("name").asString());
            assertEquals("header", csrf.path("in").asString());
            assertEquals(AuthHeaders.CSRF_TOKEN, csrf.path("name").asString());
        }
    }

    @Test
    void loginMarksMatchWhatTheServerRequires() throws Exception {
        for (var operation : documentedOperations()) {
            // 유효한 CSRF 토큰을 넣어, CSRF가 아니라 로그인 여부만으로 결과가 갈리게 합니다.
            var response = call(operation, preLoginSession(), true);
            assertEquals(operation.needsLogin(), response.statusCode() == 401,
                    operation + " → " + response.statusCode());
        }
    }

    @Test
    void csrfMarksMatchWhatTheServerRequires() throws Exception {
        for (var operation : documentedOperations()) {
            // 로그인 전 세션은 있고 토큰만 뺍니다. 세션이 없으면 로그아웃은 CSRF 확인 없이 401이 됩니다.
            var response = call(operation, preLoginSession(), false);
            assertEquals(operation.needsCsrf(), isCsrfRejection(response),
                    operation + " → " + response.statusCode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/swagger-ui/index.html", "/swagger-ui/swagger-initializer.js",
            "/swagger-ui/swagger-ui-bundle.js", "/v3/api-docs/swagger-config", USER_DOCS, ADMIN_DOCS})
    void docsAndSwaggerUiOpenWithoutLoginAndCreateNoSession(String path) throws Exception {
        var response = get(path);

        assertEquals(200, response.statusCode());
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/swagger-ui.html", "/swagger-ui/index.html", USER_DOCS})
    void docsAreOpenOnlyForGet(String path) throws Exception {
        var response = send(HttpRequest.newBuilder(uri(path)).method("OPTIONS", HttpRequest.BodyPublishers.noBody()));

        assertEquals(401, response.statusCode());
    }

    @Test
    void theSwaggerUiAddressMovesToThePageWithoutCreatingASession() throws Exception {
        var response = get("/swagger-ui.html");

        assertEquals(302, response.statusCode());
        assertEquals("/swagger-ui/index.html", response.headers().firstValue("Location").orElseThrow());
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void swaggerUiOpensTheMemberGroupFirstAndSortsByName() throws Exception {
        var config = docs("/v3/api-docs/swagger-config");
        var memberGroupName = config.path("urls").values().stream()
                .filter(url -> url.path("url").asString().equals(USER_DOCS))
                .map(url -> url.path("name").asString())
                .findFirst().orElseThrow();

        assertEquals(memberGroupName, config.path("urls.primaryName").asString());
        assertEquals("alpha", config.path("tagsSorter").asString());
        assertEquals("alpha", config.path("operationsSorter").asString());
    }

    // 문서에 나온 API 하나와, 문서가 표시한 로그인·CSRF 필요 여부
    private record DocumentedOperation(String method, String path, boolean needsLogin, boolean needsCsrf) {
    }

    // 로그인 전 세션 쿠키와 그 세션의 CSRF 토큰
    private record PreLoginSession(String cookie, String csrfToken) {
    }

    // "메서드 경로" → 두 그룹 문서에 나온 API 정보
    private Map<String, JsonNode> documentedApis() throws Exception {
        var apis = new LinkedHashMap<String, JsonNode>();
        for (var docs : List.of(USER_DOCS, ADMIN_DOCS)) {
            for (var path : docs(docs).path("paths").properties()) {
                for (var method : path.getValue().properties()) {
                    apis.put(method.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey(), method.getValue());
                }
            }
        }
        // 문서가 비어 있으면 아래 확인이 아무것도 보지 않으므로 API 수를 먼저 봅니다.
        assertEquals(12, apis.size());
        return apis;
    }

    private List<DocumentedOperation> documentedOperations() throws Exception {
        var operations = new ArrayList<DocumentedOperation>();
        for (var api : documentedApis().entrySet()) {
            var security = api.getValue().path("security");
            // 필요한 것을 한 묶음(모두 필요)으로만 표시합니다.
            assertThat(security.size()).isLessThanOrEqualTo(1);
            var requirement = security.path(0);
            var methodAndPath = api.getKey().split(" ", 2);
            operations.add(new DocumentedOperation(methodAndPath[0], methodAndPath[1],
                    requirement.has(ApiDocsConfiguration.SESSION_SCHEME),
                    requirement.has(ApiDocsConfiguration.CSRF_SCHEME)));
        }
        return operations;
    }

    private PreLoginSession preLoginSession() throws Exception {
        var response = get("/api/v1/auth/csrf");
        assertEquals(200, response.statusCode());
        var token = mapper.readTree(response.body()).path("data").path("token").asString();
        return new PreLoginSession(sessionCookie(response), token);
    }

    private HttpResponse<String> call(DocumentedOperation operation, PreLoginSession session, boolean withCsrf)
            throws Exception {
        var path = operation.path().replace("{userId}", UUID.randomUUID().toString());
        var hasBody = Set.of("POST", "PUT", "PATCH").contains(operation.method());
        var request = HttpRequest.newBuilder(uri(path)).header("Cookie", session.cookie())
                .method(operation.method(), hasBody
                        ? HttpRequest.BodyPublishers.ofString("{}") : HttpRequest.BodyPublishers.noBody());
        if (hasBody) {
            request.header("Content-Type", "application/json");
        }
        if (withCsrf) {
            request.header(AuthHeaders.CSRF_TOKEN, session.csrfToken());
        }
        return send(request);
    }

    private boolean isCsrfRejection(HttpResponse<String> response) {
        return response.statusCode() == 403 && "COMMON_CSRF_INVALID".equals(
                mapper.readTree(response.body()).path("error").path("code").asString());
    }

    private JsonNode docs(String path) throws Exception {
        var response = get(path);
        assertEquals(200, response.statusCode(), path);
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String sessionCookie(HttpResponse<String> response) {
        var cookie = response.headers().allValues("Set-Cookie").stream()
                .flatMap(value -> HttpCookie.parse(value).stream()).filter(value -> value.getName().equals("SESSION"))
                .findFirst().orElseThrow();
        return cookie.getName() + "=" + cookie.getValue();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
