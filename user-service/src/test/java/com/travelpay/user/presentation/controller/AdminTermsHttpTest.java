package com.travelpay.user.presentation.controller;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.travelpay.user.UserServiceApplication;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserCredentials;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
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
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 실제 애플리케이션 설정·Flyway 초기 약관·Redis 세션으로 관리자 약관 API를 HTTP로 확인합니다. */
// 이 패키지에는 다른 테스트의 전용 설정이 있어서, 실제 애플리케이션 클래스를 직접 지정합니다.
@SpringBootTest(classes = UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                // 로그 형식은 테스트 JVM 전체에 남습니다. 다른 테스트가 일반 텍스트 로그를 확인하므로 JSON 로그를 켜지 않습니다.
                "logging.structured.format.console="
        })
class AdminTermsHttpTest {

    private static final String PASSWORD = "TravelpayDemo-2026!";
    private static final String ADMIN_EMAIL = "terms.admin@example.com";
    private static final String MEMBER_EMAIL = "terms.member@example.com";
    private static final String SEED_VERSION = "2026-10-01";
    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withDatabaseName("travelpay_user_admin_terms_test")
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

    @BeforeEach
    void setUp() {
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (var entity : List.of("UserConsent", "UserCredentials", "User")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
        });
        // Flyway가 넣은 초기 약관만 남기고, 앞선 시험이 등록한 버전은 지웁니다.
        jdbc.update("delete from terms_documents where terms_version <> ?", SEED_VERSION);
        var adminId = member(ADMIN_EMAIL, "010-4000-0001");
        member(MEMBER_EMAIL, "010-4000-0002");
        jdbc.update("update users set role = 'MASTER' where id = ?", adminId);
    }

    @Test
    void aPublishedFutureVersionIsListedFirstForItsCodeWhileTheCurrentTermsStayTheSame() throws Exception {
        var admin = login(ADMIN_EMAIL);
        var effectiveAt = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        var published = publish(admin, body("SERVICE_TERMS", "2026-12-01", "서비스 이용약관 개정", effectiveAt));

        assertEquals(201, published.statusCode());
        var data = mapper.readTree(published.body()).get("data");
        assertEquals("SERVICE_TERMS", data.get("termsCode").asString());
        assertEquals("2026-12-01", data.get("termsVersion").asString());
        assertEquals("서비스 이용약관 개정", data.get("title").asString());
        assertEquals(true, data.get("isRequired").asBoolean());
        assertEquals(effectiveAt.toString(), data.get("effectiveAt").asString());
        var listed = mapper.readTree(list(admin, "termsCode=SERVICE_TERMS").body()).get("data").get("items");
        assertEquals("2026-12-01", listed.get(0).get("termsVersion").asString());
        assertEquals(SEED_VERSION, listed.get(1).get("termsVersion").asString());
        var current = mapper.readTree(send(HttpRequest.newBuilder(uri("/api/v1/terms")).GET()).body())
                .get("data").get("terms");
        for (var terms : current) {
            assertEquals(SEED_VERSION, terms.get("termsVersion").asString());
        }
    }

    @Test
    void aPublishedVersionCannotBeOverwritten() throws Exception {
        var admin = login(ADMIN_EMAIL);
        var effectiveAt = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        assertError(publish(admin, body("SERVICE_TERMS", SEED_VERSION, "덮어쓰려는 제목", effectiveAt)),
                409, "USER_TERMS_VERSION_CONFLICT");

        var seeded = mapper.readTree(list(admin, "termsCode=SERVICE_TERMS").body()).get("data").get("items").get(0);
        assertEquals("서비스 이용약관", seeded.get("title").asString());
    }

    @Test
    void aVersionThatWouldTakeEffectInThePastIsRejected() throws Exception {
        var past = body("SERVICE_TERMS", "2026-12-01", "약관", Instant.now().minusSeconds(60));

        assertError(publish(login(ADMIN_EMAIL), past), 400, "USER_TERMS_EFFECTIVE_AT_NOT_FUTURE");
    }

    // 올바른 본문에서 한 필드만 틀리게 바꿔(NULL은 필드를 뺌) 400인지 확인합니다.
    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "termsCode, service_terms",
            "termsVersion, 12345678901234567890123456789012345678901",
            "title, ' '",
            "isRequired, NULL",
            "effectiveAt, NULL",
            "effectiveAt, 2099-01-01"
    })
    void invalidTermsAreRejected(String field, String value) throws Exception {
        var body = validBody();
        if (value == null) {
            body.remove(field);
        }
        else {
            body.put(field, value);
        }

        assertError(publish(login(ADMIN_EMAIL), mapper.writeValueAsString(body)), 400, "COMMON_INVALID_REQUEST");
    }

    @Test
    void anEffectiveTimeWithAnOffsetIsStoredAsTheSameInstant() throws Exception {
        var koreanTime = validBody().put("effectiveAt", "2099-01-01T09:00:00+09:00");

        var published = publish(login(ADMIN_EMAIL), mapper.writeValueAsString(koreanTime));

        assertEquals(201, published.statusCode());
        assertEquals("2099-01-01T00:00:00Z",
                mapper.readTree(published.body()).get("data").get("effectiveAt").asString());
    }

    @Test
    void listingGoesThroughEveryCodePageByPage() throws Exception {
        var admin = login(ADMIN_EMAIL);

        var first = mapper.readTree(list(admin, "size=1").body()).get("data");
        var second = mapper.readTree(list(admin, "size=1&page=1").body()).get("data");

        assertEquals("PRIVACY_TERMS", first.get("items").get(0).get("termsCode").asString());
        assertEquals(true, first.get("hasNext").asBoolean());
        assertEquals("SERVICE_TERMS", second.get("items").get(0).get("termsCode").asString());
        assertEquals(false, second.get("hasNext").asBoolean());
    }

    @Test
    void aControlCharacterInTheTermsCodeFilterIsABadRequest() throws Exception {
        assertError(list(login(ADMIN_EMAIL), "termsCode=SERVICE%00TERMS"), 400, "COMMON_INVALID_REQUEST");
    }

    @Test
    void aRegularMemberCannotManageTerms() throws Exception {
        var member = login(MEMBER_EMAIL);

        assertError(list(member, ""), 403, "COMMON_FORBIDDEN");
        assertError(publish(member, body("SERVICE_TERMS", "2026-12-01", "약관", Instant.now().plusSeconds(3_600))),
                403, "COMMON_FORBIDDEN");
    }

    @Test
    void publishingRequiresTheCsrfToken() throws Exception {
        var admin = login(ADMIN_EMAIL);

        var withoutToken = send(HttpRequest.newBuilder(uri("/api/v1/admin/terms"))
                .header("Cookie", admin.cookie()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        body("SERVICE_TERMS", "2026-12-01", "약관", Instant.now().plusSeconds(3_600)))));

        assertError(withoutToken, 403, "COMMON_CSRF_INVALID");
    }

    private String body(String code, String version, String title, Instant effectiveAt) {
        return mapper.writeValueAsString(validBody().put("termsCode", code).put("termsVersion", version)
                .put("title", title).put("effectiveAt", effectiveAt.toString()));
    }

    private ObjectNode validBody() {
        return mapper.createObjectNode().put("termsCode", "SERVICE_TERMS").put("termsVersion", "v2")
                .put("title", "약관").put("content", "약관 본문").put("isRequired", true)
                .put("effectiveAt", "2099-01-01T00:00:00Z");
    }

    private HttpResponse<String> publish(Member admin, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri("/api/v1/admin/terms"))
                .header("Cookie", admin.cookie()).header(admin.csrfHeader(), admin.csrfToken())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> list(Member admin, String query) throws Exception {
        return send(HttpRequest.newBuilder(uri("/api/v1/admin/terms" + (query.isEmpty() ? "" : "?" + query)))
                .header("Cookie", admin.cookie()).GET());
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

    /** 로그인 후 세션 ID와 CSRF 토큰이 바뀌므로, 로그인한 세션으로 CSRF 토큰을 다시 받습니다. */
    private Member login(String email) throws Exception {
        var anonymous = csrf(null);
        var body = mapper.createObjectNode().put("loginEmail", email).put("password", PASSWORD);
        var response = send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Cookie", anonymous.cookie()).header(anonymous.csrfHeader(), anonymous.csrfToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
        assertEquals(200, response.statusCode());
        return csrf(sessionCookie(response));
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
