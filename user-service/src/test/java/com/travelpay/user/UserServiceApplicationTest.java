package com.travelpay.user;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.travelpay.user.application.service.LoginAttemptLimiter;
import com.travelpay.user.common.config.LoginAttemptProperties;
import com.travelpay.user.infrastructure.redis.RedisLoginAttemptLimiter;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** 테스트용으로 고른 빈이 아니라 실제 애플리케이션의 컴포넌트 스캔·자동 설정·application.yml로 시작합니다. */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        // 로그 형식은 테스트 JVM 전체에 남습니다. 다른 테스트가 일반 텍스트 로그를 확인하므로 JSON 로그를 켜지 않습니다.
        "logging.structured.format.console="
})
@ExtendWith(OutputCaptureExtension.class)
class UserServiceApplicationTest {

    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withDatabaseName("travelpay_user_app_test")
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

    @Autowired private ApplicationContext context;

    @Test
    void startsWithoutTheUnusedDefaultUserOrItsGeneratedPassword(CapturedOutput output) {
        assertEquals(Map.of(), context.getBeansOfType(UserDetailsService.class));
        assertFalse(output.getAll().contains("generated security password"));
    }

    @Test
    void wiresTheRedisLoginAttemptLimiterWithTheConfiguredPolicy() {
        assertInstanceOf(RedisLoginAttemptLimiter.class, context.getBean(LoginAttemptLimiter.class));
        assertEquals(new LoginAttemptProperties(5, 20, Duration.ofMinutes(10), Duration.ofMinutes(20)),
                context.getBean(LoginAttemptProperties.class));
    }

    @Test
    void startsOnTheSchemaFlywayMigratedWhileHibernateOnlyValidatesIt() {
        var applied = Arrays.stream(context.getBean(Flyway.class).info().applied())
                .map(migration -> migration.getVersion().getVersion())
                .toList();

        assertEquals(List.of("1", "2"), applied);
        assertEquals("validate", context.getBean(EntityManagerFactory.class).getProperties().get("hibernate.hbm2ddl.auto"));
    }
}
