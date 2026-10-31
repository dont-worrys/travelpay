package com.travelpay.user.common.security;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.CurrentUserProvider;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.auth.web.CurrentUserHeaderInterceptor;
import com.travelpay.common.config.CommonAuthWebAutoConfiguration;
import com.travelpay.common.config.CommonWebAutoConfiguration;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.user.application.service.CurrentMemberService;
import com.travelpay.user.common.security.access.RestAccessDeniedHandler;
import com.travelpay.user.common.security.access.RestAuthenticationEntryPoint;
import com.travelpay.user.common.security.access.SecurityErrorResponseWriter;
import com.travelpay.user.common.security.session.SessionCurrentUserProvider;
import com.travelpay.user.common.security.session.UserSessionPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.jackson.JacksonEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserSecurityBoundaryTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String FORGED_USER_ID = "11111111-1111-1111-1111-111111111111";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void anonymousMonitoringClientsCanReadTheActualHealthAndPrometheusEndpoints(String filename) throws Exception {
        runner(filename).run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = mvc(context.getSourceApplicationContext());
            var mapper = context.getBean(JsonMapper.class);
            context.getBean(MeterRegistry.class).counter("travelpay.test.scrape").increment();

            // 실제 엔드포인트가 등록되었는지 먼저 확인해, 401과 미등록 404를 구분합니다.
            mvc.perform(get("/actuator/health").session(userSession(context.getSourceApplicationContext())))
                    .andExpect(status().isOk());
            mvc.perform(get("/actuator/prometheus").session(userSession(context.getSourceApplicationContext())))
                    .andExpect(status().isOk());
            var health = mvc.perform(get("/actuator/health")).andReturn();
            var metrics = mvc.perform(get("/actuator/prometheus")).andReturn();
            assertAll(
                    () -> assertEquals(200, health.getResponse().getStatus(), "health 익명 접근"),
                    () -> assertEquals(200, metrics.getResponse().getStatus(), "prometheus 익명 접근"));
            assertEquals("UP", mapper.readTree(health.getResponse().getContentAsString(UTF_8))
                    .get("status").asString());
            assertTrue(metrics.getResponse().getContentAsString(UTF_8).contains("travelpay_test_scrape_total"));
            mvc.perform(get("/actuator/health/unknown-group")).andExpect(status().isNotFound());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void otherManagementPathsRemainProtected(String filename) throws Exception {
        runner(filename).run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = mvc(context.getSourceApplicationContext());

            mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
            mvc.perform(get("/actuator/health-extra")).andExpect(status().isUnauthorized());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void forgedMemberHeadersCannotReplaceTheSessionMember(String filename) throws Exception {
        runner(filename).run(context -> {
            assertThat(context).hasNotFailed();
            var app = context.getSourceApplicationContext();
            var result = mvc(app).perform(get("/test/session-identity")
                            .session(userSession(app))
                            .header("User-Id", FORGED_USER_ID)
                            .header("User-Role", "MASTER"))
                    .andExpect(status().isOk()).andReturn();
            var data = context.getBean(JsonMapper.class)
                    .readTree(result.getResponse().getContentAsString(UTF_8)).get("data");

            assertEquals(USER_ID.toString(), data.get("sessionPrincipal").asString());
            assertEquals(USER_ID.toString(), data.get("currentUser").get("userId").asString());
            assertEquals("USER", data.get("currentUser").get("role").asString());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void currentUserReceivesTheSessionMemberThroughTheCommonResolver(String filename) throws Exception {
        runner(filename).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CurrentUserProvider.class)
                    .doesNotHaveBean(CurrentUserHeaderInterceptor.class);
            assertThat(context.getBean(CurrentUserProvider.class)).isInstanceOf(SessionCurrentUserProvider.class);
            var app = context.getSourceApplicationContext();

            var result = mvc(app).perform(get("/test/current-user")
                            .session(userSession(app))
                            .header("User-Id", FORGED_USER_ID)
                            .header("User-Role", "MASTER"))
                    .andExpect(status().isOk()).andReturn();
            var data = context.getBean(JsonMapper.class)
                    .readTree(result.getResponse().getContentAsString(UTF_8)).get("data");

            assertEquals(USER_ID.toString(), data.get("userId").asString());
            assertEquals("USER", data.get("role").asString());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void memberHeadersAloneDoNotAuthenticateARequest(String filename) throws Exception {
        runner(filename).run(context -> {
            assertThat(context).hasNotFailed();

            mvc(context.getSourceApplicationContext()).perform(get("/test/current-user")
                            .header("User-Id", USER_ID.toString())
                            .header("User-Role", "USER"))
                    .andExpect(status().isUnauthorized());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void anAuthenticatedStringPrincipalDoesNotBecomeARegisteredMember(String filename) throws Exception {
        runner(filename).run(context -> {
            assertThat(context).hasNotFailed();
            var app = context.getSourceApplicationContext();

            mvc(app).perform(get("/test/current-user")
                            .session(sessionWithPrincipal(app, USER_ID.toString()))
                            .header("User-Id", USER_ID.toString())
                            .header("User-Role", "USER"))
                    .andExpect(status().isUnauthorized());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void forgedMasterHeaderDoesNotGrantAdministratorAccess(String filename) throws Exception {
        runner(filename).run(context -> {
            assertThat(context).hasNotFailed();
            var app = context.getSourceApplicationContext();

            mvc(app).perform(get("/api/v1/admin/probe")
                            .session(userSession(app))
                            .header("User-Id", FORGED_USER_ID)
                            .header("User-Role", "MASTER"))
                    .andExpect(status().isForbidden());
        });
    }

    private WebApplicationContextRunner runner(String filename) throws IOException {
        var sources = new YamlPropertySourceLoader().load(filename, new ClassPathResource(filename));
        return new WebApplicationContextRunner()
                .withBean(CurrentMemberService.class, () -> {
                    var service = mock(CurrentMemberService.class);
                    when(service.requireActive(USER_ID)).thenReturn(new AuthenticatedUser(USER_ID, AuthRole.USER));
                    return service;
                })
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
                        WebMvcAutoConfiguration.class, EndpointAutoConfiguration.class,
                        JacksonEndpointAutoConfiguration.class, WebEndpointAutoConfiguration.class,
                        HealthContributorAutoConfiguration.class, HealthContributorRegistryAutoConfiguration.class,
                        HealthEndpointAutoConfiguration.class, MetricsAutoConfiguration.class,
                        PrometheusMetricsExportAutoConfiguration.class, ManagementContextAutoConfiguration.class,
                        CommonWebAutoConfiguration.class, CommonAuthWebAutoConfiguration.class))
                .withUserConfiguration(SecurityConfiguration.class, RestAuthenticationEntryPoint.class,
                        RestAccessDeniedHandler.class, SecurityErrorResponseWriter.class,
                        SessionCurrentUserProvider.class, ProbeController.class)
                .withInitializer(context -> sources.forEach(
                        source -> context.getEnvironment().getPropertySources().addLast(source)));
    }

    private MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpSession userSession(WebApplicationContext context) {
        return sessionWithPrincipal(context, new UserSessionPrincipal(
                USER_ID, AuthRole.USER, Instant.parse("2026-10-30T00:00:00Z")));
    }

    private MockHttpSession sessionWithPrincipal(WebApplicationContext context, Object principal) {
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        var session = new MockHttpSession(context.getServletContext());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        return session;
    }

    /** 실제 사용자 API를 추가하지 않고 인증 경계만 확인하는 테스트 컨트롤러입니다. */
    @TestComponent
    @RestController
    static class ProbeController {

        private final CurrentUserProvider currentUserProvider;

        ProbeController(CurrentUserProvider currentUserProvider) {
            this.currentUserProvider = currentUserProvider;
        }

        @GetMapping("/test/session-identity")
        ApiResponse<IdentityProbe> identity(Authentication authentication) {
            return ApiResponse.success(new IdentityProbe(authentication.getName(),
                    currentUserProvider.findCurrentUser().orElse(null)));
        }

        @GetMapping("/test/current-user")
        ApiResponse<AuthenticatedUser> currentUser(@CurrentUser AuthenticatedUser user) {
            return ApiResponse.success(user);
        }

        @GetMapping("/api/v1/admin/probe")
        ApiResponse<Void> admin() {
            return ApiResponse.success(null);
        }
    }

    private record IdentityProbe(String sessionPrincipal, AuthenticatedUser currentUser) {
    }
}
