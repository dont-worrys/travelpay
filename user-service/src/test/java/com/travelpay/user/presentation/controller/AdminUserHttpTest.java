package com.travelpay.user.presentation.controller;

import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.user.UserServiceApplication;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserCredentials;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 실제 애플리케이션 설정·Flyway 스키마·Redis 세션으로 관리자 회원 관리 API를 HTTP로 확인합니다. */
// 이 패키지에는 다른 테스트의 전용 설정이 있어서, 실제 애플리케이션 클래스를 직접 지정합니다.
@SpringBootTest(classes = UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                // 로그 형식은 테스트 JVM 전체에 남습니다. 다른 테스트가 일반 텍스트 로그를 확인하므로 JSON 로그를 켜지 않습니다.
                "logging.structured.format.console="
        })
@ExtendWith(OutputCaptureExtension.class)
class AdminUserHttpTest {

    private static final String PASSWORD = "TravelpayDemo-2026!";
    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String TARGET_EMAIL = "target@example.com";
    private static final String OTHER_EMAIL = "other@example.com";
    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withDatabaseName("travelpay_user_admin_test")
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
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private UUID adminId;
    private UUID targetId;

    @BeforeEach
    void setUp() {
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
        });
        adminId = member(ADMIN_EMAIL, "010-2000-0001");
        targetId = member(TARGET_EMAIL, "010-2000-0002");
        member(OTHER_EMAIL, "010-2000-0003");
        // 공개 가입은 USER만 만들므로, 최초 MASTER 준비 절차처럼 DB에서 역할을 바꿉니다.
        jdbc.update("update users set role = 'MASTER' where id = ?", adminId);
    }

    @Test
    void aMasterEndsEveryExistingSessionOfTheTargetAndNoOtherSession() throws Exception {
        var phone = login(TARGET_EMAIL);
        var laptop = login(TARGET_EMAIL);
        var other = login(OTHER_EMAIL);
        var admin = login(ADMIN_EMAIL);

        var ended = endSessions(admin, targetId.toString());

        assertEquals(200, ended.statusCode());
        var data = mapper.readTree(ended.body()).get("data");
        assertEquals(targetId.toString(), data.get("userId").asString());
        assertEquals("EXISTING_SESSIONS", data.get("scope").asString());
        assertError(verify(phone), 401, "COMMON_UNAUTHORIZED");
        assertError(verify(laptop), 401, "COMMON_UNAUTHORIZED");
        assertEquals(200, verify(other).statusCode());
        assertEquals(200, verify(admin).statusCode());
    }

    @Test
    void theTargetCanLogInAgainAfterItsSessionsEnd() throws Exception {
        endSessions(login(ADMIN_EMAIL), targetId.toString());

        assertEquals(200, verify(login(TARGET_EMAIL)).statusCode());
    }

    @Test
    void aMasterThatEndsItsOwnSessionsIsLoggedOutFromTheNextRequest() throws Exception {
        var admin = login(ADMIN_EMAIL);

        assertEquals(200, endSessions(admin, adminId.toString()).statusCode());

        assertError(verify(admin), 401, "COMMON_UNAUTHORIZED");
        assertError(endSessions(admin, targetId.toString()), 401, "COMMON_UNAUTHORIZED");
    }

    @Test
    void aRegularMemberCannotEndSessions() throws Exception {
        var target = login(TARGET_EMAIL);

        assertError(endSessions(login(OTHER_EMAIL), targetId.toString()), 403, "COMMON_FORBIDDEN");
        assertEquals(200, verify(target).statusCode());
    }

    @Test
    void endingSessionsRequiresTheCsrfToken() throws Exception {
        var target = login(TARGET_EMAIL);
        var admin = login(ADMIN_EMAIL);

        var withoutToken = send(HttpRequest.newBuilder(uri("/api/v1/admin/users/" + targetId + "/sessions"))
                .header("Cookie", admin.cookie()).DELETE());

        assertError(withoutToken, 403, "COMMON_CSRF_INVALID");
        assertEquals(200, verify(target).statusCode());
    }

    @Test
    void anUnknownMemberIsReportedAsNotFound() throws Exception {
        assertError(endSessions(login(ADMIN_EMAIL), UUID.randomUUID().toString()), 404, "USER_NOT_FOUND");
    }

    @Test
    void aMalformedMemberIdIsRejectedAsABadRequest() throws Exception {
        assertError(endSessions(login(ADMIN_EMAIL), "not-a-member-id"), 400, "COMMON_INVALID_REQUEST");
    }

    @Test
    void aSuspendedMemberIsBlockedUntilReactivatedAndThenMustLogInAgain() throws Exception {
        var target = login(TARGET_EMAIL);
        var admin = login(ADMIN_EMAIL);

        var suspended = changeStatus(admin, targetId, "{\"status\":\"SUSPENDED\"}");

        assertEquals(200, suspended.statusCode());
        var data = mapper.readTree(suspended.body()).get("data");
        assertEquals(targetId.toString(), data.get("userId").asString());
        assertEquals("SUSPENDED", data.get("status").asString());
        assertError(verify(target), 403, "USER_ACCOUNT_SUSPENDED");
        assertError(loginResponse(TARGET_EMAIL), 403, "USER_ACCOUNT_SUSPENDED");

        var reactivated = changeStatus(admin, targetId, "{\"status\":\"ACTIVE\"}");

        assertEquals("ACTIVE", mapper.readTree(reactivated.body()).get("data").get("status").asString());
        assertError(verify(target), 401, "COMMON_UNAUTHORIZED");
        assertEquals(200, verify(login(TARGET_EMAIL)).statusCode());
    }

    @Test
    void aMasterCannotSuspendItself() throws Exception {
        var admin = login(ADMIN_EMAIL);

        assertError(changeStatus(admin, adminId, "{\"status\":\"SUSPENDED\"}"),
                409, "USER_MASTER_PROTECTION_CONFLICT");
        assertEquals(200, verify(admin).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"status\":\"CLOSED\"}", "{\"status\":\"suspended\"}", "{}", "{\"status\":null}"})
    void onlyActiveOrSuspendedCanBeRequested(String body) throws Exception {
        var target = login(TARGET_EMAIL);

        assertError(changeStatus(login(ADMIN_EMAIL), targetId, body), 400, "COMMON_INVALID_REQUEST");
        assertEquals(200, verify(target).statusCode());
    }

    @Test
    void aRegularMemberCannotChangeStatus() throws Exception {
        assertError(changeStatus(login(OTHER_EMAIL), targetId, "{\"status\":\"SUSPENDED\"}"),
                403, "COMMON_FORBIDDEN");
        assertEquals(200, verify(login(TARGET_EMAIL)).statusCode());
    }

    @Test
    void changingStatusRequiresTheCsrfToken() throws Exception {
        var admin = login(ADMIN_EMAIL);

        var withoutToken = send(HttpRequest.newBuilder(uri("/api/v1/admin/users/" + targetId + "/status"))
                .header("Cookie", admin.cookie()).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"status\":\"SUSPENDED\"}")));

        assertError(withoutToken, 403, "COMMON_CSRF_INVALID");
        assertEquals(200, verify(login(TARGET_EMAIL)).statusCode());
    }

    @Test
    void searchReturnsMaskedMembersPageByPage() throws Exception {
        var admin = login(ADMIN_EMAIL);

        var first = search(admin, "size=2");
        var second = search(admin, "size=2&page=1");

        assertEquals(200, first.statusCode());
        var firstPage = mapper.readTree(first.body()).get("data");
        assertEquals(2, firstPage.get("items").size());
        assertEquals(0, firstPage.get("page").asInt());
        assertEquals(2, firstPage.get("size").asInt());
        assertEquals(true, firstPage.get("hasNext").asBoolean());
        var secondPage = mapper.readTree(second.body()).get("data");
        assertEquals(1, secondPage.get("items").size());
        assertEquals(false, secondPage.get("hasNext").asBoolean());
        // 남은 회원이 한 페이지에 꼭 맞으면 다음 페이지는 없음
        var exactPage = mapper.readTree(search(admin, "size=3").body()).get("data");
        assertEquals(3, exactPage.get("items").size());
        assertEquals(false, exactPage.get("hasNext").asBoolean());
        assertEquals(20, mapper.readTree(search(admin, "").body()).get("data").get("size").asInt());
    }

    @Test
    void searchFindsTheTargetByNormalizedEmailOrPhoneAndMasksIt() throws Exception {
        var admin = login(ADMIN_EMAIL);
        var byEmail = search(admin, "loginEmail=" + URLEncoder.encode("  TARGET@Example.com ", UTF_8));
        var byPhone = search(admin, "phone=01020000002&status=ACTIVE");

        for (var response : List.of(byEmail, byPhone)) {
            var items = mapper.readTree(response.body()).get("data").get("items");
            assertEquals(1, items.size());
            var item = items.get(0);
            assertEquals(targetId.toString(), item.get("userId").asString());
            assertEquals("홍*동", item.get("maskedRealName").asString());
            assertEquals("t***@example.com", item.get("maskedEmail").asString());
            assertEquals("010-****-0002", item.get("maskedPhone").asString());
            assertEquals("ACTIVE", item.get("status").asString());
            assertEquals("USER", item.get("role").asString());
        }
        assertFalse(byEmail.body().contains(TARGET_EMAIL));
        assertFalse(byPhone.body().contains("010-2000-0002"));
    }

    @Test
    void searchValuesNeverReachTheLogs(CapturedOutput output) throws Exception {
        var admin = login(ADMIN_EMAIL);

        search(admin, "loginEmail=" + URLEncoder.encode(TARGET_EMAIL, UTF_8) + "&phone=010-2000-0002");
        search(admin, "loginEmail=" + URLEncoder.encode("no.such.member@example.com", UTF_8));

        assertFalse(output.getAll().contains(TARGET_EMAIL));
        assertFalse(output.getAll().contains("no.such.member@example.com"));
        assertFalse(output.getAll().contains("010-2000-0002"));
    }

    // 화면의 검색 폼은 비워 둔 칸도 빈 값으로 보내므로, 빈 값은 모두 조건 없음·기본값으로 받아야 함
    @Test
    void anEmptySearchFormSearchesEveryMember() throws Exception {
        var emptyForm = search(login(ADMIN_EMAIL), "userId=&loginEmail=&phone=&status=&page=&size=");

        assertEquals(200, emptyForm.statusCode());
        var data = mapper.readTree(emptyForm.body()).get("data");
        assertEquals(3, data.get("items").size());
        assertEquals(0, data.get("page").asInt());
        assertEquals(20, data.get("size").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"size=0", "size=101", "page=-1", "status=DELETED", "phone=02-123-4567",
            "userId=not-a-member-id", "loginEmail=a%00b%40example.com"})
    void invalidSearchConditionsAreRejected(String query) throws Exception {
        assertError(search(login(ADMIN_EMAIL), query), 400, "COMMON_INVALID_REQUEST");
    }

    @Test
    void aRegularMemberCannotSearchMembers() throws Exception {
        assertError(search(login(OTHER_EMAIL), ""), 403, "COMMON_FORBIDDEN");
    }

    private HttpResponse<String> search(Member admin, String query) throws Exception {
        return send(HttpRequest.newBuilder(uri("/api/v1/admin/users" + (query.isEmpty() ? "" : "?" + query)))
                .header("Cookie", admin.cookie()).GET());
    }

    private HttpResponse<String> changeStatus(Member admin, UUID userId, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri("/api/v1/admin/users/" + userId + "/status"))
                .header("Cookie", admin.cookie()).header(admin.csrfHeader(), admin.csrfToken())
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)));
    }

    private UUID member(String email, String phone) {
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        return new TransactionTemplate(transactionManager).execute(status -> {
            var user = User.register("홍길동", phone, Instant.now());
            entityManager.persist(user);
            entityManager.persist(UserCredentials.createFor(user, email, passwordEncoder.encode(PASSWORD),
                    Instant.now()));
            return user.getId();
        });
    }

    private HttpResponse<String> endSessions(Member admin, String userId) throws Exception {
        return send(HttpRequest.newBuilder(uri("/api/v1/admin/users/" + userId + "/sessions"))
                .header("Cookie", admin.cookie()).header(admin.csrfHeader(), admin.csrfToken()).DELETE());
    }

    /** 로그인 후 세션 ID와 CSRF 토큰이 바뀌므로, 로그인한 세션으로 CSRF 토큰을 다시 받습니다. */
    private Member login(String email) throws Exception {
        var response = loginResponse(email);
        assertEquals(200, response.statusCode());
        return csrf(sessionCookie(response));
    }

    private HttpResponse<String> loginResponse(String email) throws Exception {
        var anonymous = csrf(null);
        var body = mapper.createObjectNode().put("loginEmail", email).put("password", PASSWORD);
        return send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Cookie", anonymous.cookie()).header(anonymous.csrfHeader(), anonymous.csrfToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
    }

    private Member csrf(String cookie) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET();
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        var response = send(request);
        assertEquals(200, response.statusCode());
        var data = mapper.readTree(response.body()).get("data");
        var current = cookie == null ? sessionCookie(response) : cookie;
        return new Member(current, data.get("headerName").asString(), data.get("token").asString());
    }

    private HttpResponse<String> verify(Member member) throws Exception {
        return send(HttpRequest.newBuilder(uri("/internal/v1/auth/session"))
                .header("Cookie", member.cookie()).header("Original-Method", "GET")
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode());
        assertEquals(code, mapper.readTree(response.body()).get("error").get("code").asString());
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

    private record Member(String cookie, String csrfHeader, String csrfToken) {
    }
}
