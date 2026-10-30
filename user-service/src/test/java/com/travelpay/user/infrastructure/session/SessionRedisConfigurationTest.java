package com.travelpay.user.infrastructure.session;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.travelpay.user.common.config.UserSessionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.session.autoconfigure.SessionAutoConfiguration;
import org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration;
import org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.context.servlet.AnnotationConfigServletWebApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.Session;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionRedisConfigurationTest {

    @ParameterizedTest
    @CsvSource({"application.yml,false", "application-prod.yml,true"})
    void environmentConfigurationProducesTheExpectedSessionCookie(String filename, boolean expectedSecure)
            throws IOException {
        var sources = new YamlPropertySourceLoader().load(filename, new ClassPathResource(filename));

        // ServletContext를 미리 넣지 않아 실행 JAR의 자동 구성 분기를 검증합니다.
        new WebApplicationContextRunner(AnnotationConfigServletWebApplicationContext::new)
                .withConfiguration(AutoConfigurations.of(
                        SessionDataRedisAutoConfiguration.class, SessionAutoConfiguration.class))
                .withBean(RedisConnectionFactory.class, () -> new LettuceConnectionFactory("127.0.0.1", 1))
                .withInitializer(context -> sources.forEach(
                        source -> context.getEnvironment().getPropertySources().addLast(source)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var request = new MockHttpServletRequest("GET", "/test-context/api/v1/auth/csrf");
                    request.setContextPath("/test-context");
                    request.setScheme("http");
                    request.setSecure(false);
                    var response = new MockHttpServletResponse();

                    context.getBean(CookieSerializer.class).writeCookieValue(
                            new CookieSerializer.CookieValue(request, response, "session-id-for-policy-test"));

                    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).hasSize(1);
                    var cookie = response.getHeader(HttpHeaders.SET_COOKIE);
                    var parts = List.of(cookie.split(";\\s*"));
                    assertThat(cookie).startsWith("SESSION=");
                    assertThat(parts).contains("Path=/", "HttpOnly", "SameSite=Lax");
                    assertEquals(expectedSecure, parts.contains("Secure"));
                    assertThat(parts).noneMatch(part -> part.startsWith("Domain="));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void environmentConfigurationAppliesTheUserIdleTimeoutToRedisSessions(String filename) throws IOException {
        var sources = new YamlPropertySourceLoader().load(filename, new ClassPathResource(filename));

        new WebApplicationContextRunner(AnnotationConfigServletWebApplicationContext::new)
                .withConfiguration(AutoConfigurations.of(
                        SessionDataRedisAutoConfiguration.class, SessionAutoConfiguration.class))
                .withBean(RedisConnectionFactory.class, () -> new LettuceConnectionFactory("127.0.0.1", 1))
                // Servlet 기본값과 달라도 User의 명시적인 세션 정책이 우선해야 합니다.
                .withPropertyValues("server.servlet.session.timeout=5m")
                .withInitializer(context -> sources.forEach(
                        source -> context.getEnvironment().getPropertySources().addLast(source)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Session session = context.getBean(RedisSessionRepository.class).createSession();
                    var now = Instant.now();

                    assertEquals(Duration.ofMinutes(60), session.getMaxInactiveInterval());
                    session.setLastAccessedTime(now.minus(Duration.ofMinutes(59)));
                    assertThat(session.isExpired()).isFalse();
                    session.setLastAccessedTime(now.minus(Duration.ofMinutes(61)));
                    assertThat(session.isExpired()).isTrue();
                });
    }

    @Test
    void bootAutomaticallyRegistersTheRedisSessionRepositoryAndHttpSessionFilter() {
        new WebApplicationContextRunner(AnnotationConfigServletWebApplicationContext::new)
                .withConfiguration(AutoConfigurations.of(
                        SessionDataRedisAutoConfiguration.class, SessionAutoConfiguration.class))
                // 빈 연결만 검증하므로 실제 Redis에 접속하지 않는 주소를 사용합니다.
                .withBean(RedisConnectionFactory.class, () -> new LettuceConnectionFactory("127.0.0.1", 1))
                .withPropertyValues("spring.session.data.redis.namespace=travelpay:user:session")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(RedisSessionRepository.class)
                            .hasSingleBean(SessionRepositoryFilter.class)
                            .hasBean("springSessionRepositoryFilter");
                    assertEquals("travelpay:user:session",
                            context.getBean(SessionDataRedisProperties.class).getNamespace());
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void environmentConfigurationBindsTheUserSessionNamespace(String filename) throws IOException {
        var environment = new MockEnvironment();
        var sources = new YamlPropertySourceLoader().load(filename, new ClassPathResource(filename));
        sources.forEach(source -> environment.getPropertySources().addLast(source));

        var properties = Binder.get(environment)
                .bindOrCreate("spring.session.data.redis", SessionDataRedisProperties.class);

        assertEquals("travelpay:user:session", properties.getNamespace());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void environmentConfigurationBindsTheTwentyFourHourAbsoluteTimeout(String filename) throws IOException {
        var environment = new MockEnvironment();
        var sources = new YamlPropertySourceLoader().load(filename, new ClassPathResource(filename));
        sources.forEach(source -> environment.getPropertySources().addLast(source));

        var properties = Binder.get(environment).bindOrCreate("travelpay.user.session", UserSessionProperties.class);

        assertEquals(Duration.ofHours(24), properties.absoluteTimeout());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "-PT1S"})
    void rejectsANonPositiveAbsoluteTimeout(Duration absoluteTimeout) {
        assertThrows(IllegalArgumentException.class, () -> new UserSessionProperties(absoluteTimeout));
    }
}
