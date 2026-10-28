package com.travelpay.common.config;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.CurrentUserProvider;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.auth.web.CurrentUserArgumentResolver;
import com.travelpay.common.auth.web.CurrentUserHeaderInterceptor;
import com.travelpay.common.auth.web.RequestAttributeCurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CommonAuthWebAutoConfigurationTest {

    private static final String HEADER_CONTEXT_ENABLED = "travelpay.auth.header-context.enabled=true";

    private static final AuthenticatedUser STORED_USER = new AuthenticatedUser(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), AuthRole.USER);
    private static final AuthenticatedUser CUSTOM_USER = new AuthenticatedUser(
            UUID.fromString("4c6a0178-266b-4f1e-b811-a2767026be70"), AuthRole.MASTER);

    private final WebApplicationContextRunner servlet = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonAuthWebAutoConfiguration.class));
    private final WebApplicationContextRunner discovery = new WebApplicationContextRunner()
            .withUserConfiguration(DiscoveryApplication.class);

    @Test
    void servletApplicationGetsTheDefaultProviderResolverAndMvcConfigurer() {
        servlet.run(context -> {
            assertThat(context).hasNotFailed()
                    .hasSingleBean(CurrentUserProvider.class)
                    .hasSingleBean(CurrentUserArgumentResolver.class)
                    .hasSingleBean(WebMvcConfigurer.class)
                    .doesNotHaveBean(CurrentUserHeaderInterceptor.class);
            assertThat(context.getBean(CurrentUserProvider.class))
                    .isInstanceOf(RequestAttributeCurrentUserProvider.class);
        });
    }

    @Test
    void reactiveApplicationDoesNotRegisterMvcAuthenticationSupport() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonAuthWebAutoConfiguration.class))
                .withPropertyValues(HEADER_CONTEXT_ENABLED)
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(CurrentUserProvider.class)
                        .doesNotHaveBean("currentUserArgumentResolver")
                        .doesNotHaveBean("commonAuthWebMvcConfigurer")
                        .doesNotHaveBean("currentUserHeaderInterceptor")
                        .doesNotHaveBean("commonAuthHeaderWebMvcConfigurer"));
    }

    @Test
    void nonWebApplicationDoesNotRegisterMvcAuthenticationSupport() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonAuthWebAutoConfiguration.class))
                .withPropertyValues(HEADER_CONTEXT_ENABLED)
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(CurrentUserProvider.class)
                        .doesNotHaveBean("currentUserArgumentResolver")
                        .doesNotHaveBean("commonAuthWebMvcConfigurer")
                        .doesNotHaveBean("currentUserHeaderInterceptor")
                        .doesNotHaveBean("commonAuthHeaderWebMvcConfigurer"));
    }

    @Test
    void missingMvcClassesDoNotBreakStartup() {
        servlet.withClassLoader(new FilteredClassLoader("org.springframework.web.servlet"))
                .withPropertyValues(HEADER_CONTEXT_ENABLED)
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(CurrentUserProvider.class)
                        .doesNotHaveBean("currentUserArgumentResolver")
                        .doesNotHaveBean("commonAuthWebMvcConfigurer")
                        .doesNotHaveBean("currentUserHeaderInterceptor")
                        .doesNotHaveBean("commonAuthHeaderWebMvcConfigurer"));
    }

    @Test
    void missingServletRequestClassDoesNotBreakStartup() {
        servlet.withClassLoader(new FilteredClassLoader(HttpServletRequest.class))
                .withPropertyValues(HEADER_CONTEXT_ENABLED)
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(CurrentUserProvider.class)
                        .doesNotHaveBean("currentUserArgumentResolver")
                        .doesNotHaveBean("commonAuthWebMvcConfigurer")
                        .doesNotHaveBean("currentUserHeaderInterceptor")
                        .doesNotHaveBean("commonAuthHeaderWebMvcConfigurer"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "true"})
    void bootDiscoveryRegistersTheResolverWithMvcAndInjectsTheStoredIdentity(String enabled) {
        discovery.withPropertyValues("travelpay.auth.header-context.enabled=" + enabled).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CurrentUserProvider.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();

            mvc.perform(get("/test/current-user")
                            .requestAttr(RequestAttributeCurrentUserProvider.CURRENT_USER_ATTRIBUTE, STORED_USER)
                            .param("userId", CUSTOM_USER.userId().toString())
                            .param("role", "MASTER")
                            .header(AuthHeaders.USER_ID, CUSTOM_USER.userId().toString())
                            .header(AuthHeaders.USER_ROLE, "MASTER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(STORED_USER.userId().toString()))
                    .andExpect(jsonPath("$.role").value("USER"));
        });
    }

    @Test
    void automaticallyRegisteredResolverRejectsHeadersWithoutStoredIdentity() {
        discovery.run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();

            mvc.perform(get("/test/current-user")
                            .param("userId", CUSTOM_USER.userId().toString())
                            .param("role", "MASTER")
                            .header(AuthHeaders.USER_ID, CUSTOM_USER.userId().toString())
                            .header(AuthHeaders.USER_ROLE, "MASTER"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        });
    }

    @Test
    void enabledBootDiscoveryRegistersTheInterceptorAndInjectsHeaderIdentity() {
        discovery.withPropertyValues(HEADER_CONTEXT_ENABLED).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CurrentUserHeaderInterceptor.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();

            mvc.perform(get("/test/current-user")
                            .header(AuthHeaders.USER_ID, STORED_USER.userId().toString())
                            .header(AuthHeaders.USER_ROLE, "USER")
                            .param("userId", CUSTOM_USER.userId().toString())
                            .param("role", "MASTER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(STORED_USER.userId().toString()))
                    .andExpect(jsonPath("$.role").value("USER"));
        });
    }

    @Test
    void enabledHeaderContextMakesIdentityAvailableToTheInjectedProvider() {
        discovery.withPropertyValues(HEADER_CONTEXT_ENABLED)
                .withUserConfiguration(ProviderProbeController.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var mvc = MockMvcBuilders.webAppContextSetup(context).build();

                    mvc.perform(get("/test/provider-user")
                                    .header(AuthHeaders.USER_ID, CUSTOM_USER.userId().toString())
                                    .header(AuthHeaders.USER_ROLE, "MASTER"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.userId").value(CUSTOM_USER.userId().toString()))
                            .andExpect(jsonPath("$.role").value("MASTER"));
                });
    }

    @Test
    void enabledHeaderContextKeepsPublicRoutesAnonymousAndCurrentUserRequired() {
        discovery.withPropertyValues(HEADER_CONTEXT_ENABLED).run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();

            mvc.perform(get("/test/public"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("public"));

            mvc.perform(get("/test/current-user"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        });
    }

    @Test
    void enabledInterceptorRejectsIncompleteHeadersBeforeAnOrdinaryController() {
        discovery.withPropertyValues(HEADER_CONTEXT_ENABLED).run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();

            var result = mvc.perform(get("/test/public")
                            .header(AuthHeaders.USER_ID, "DO_NOT_ECHO_INVALID_HEADER"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"))
                    .andExpect(jsonPath("$.errors").isEmpty())
                    .andReturn();

            assertThat(result.getResponse().getContentAsString()).doesNotContain("DO_NOT_ECHO");
        });
    }

    @Test
    void explicitlyDisabledHeaderContextDoesNotRegisterEvenAnApplicationProvidedInterceptor() {
        discovery.withPropertyValues("travelpay.auth.header-context.enabled=false")
                .withUserConfiguration(CustomHeaderInterceptorConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(CurrentUserHeaderInterceptor.class);
                    var mvc = MockMvcBuilders.webAppContextSetup(context).build();

                    mvc.perform(get("/test/current-user")
                                    .header(AuthHeaders.USER_ID, STORED_USER.userId().toString())
                                    .header(AuthHeaders.USER_ROLE, "USER"))
                            .andExpect(status().isUnauthorized())
                            .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
                });
    }

    @Test
    void enabledHeaderContextUsesAnApplicationProvidedInterceptor() {
        discovery.withPropertyValues(HEADER_CONTEXT_ENABLED)
                .withUserConfiguration(CustomHeaderInterceptorConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(CurrentUserHeaderInterceptor.class);
                    var mvc = MockMvcBuilders.webAppContextSetup(context).build();

                    mvc.perform(get("/test/current-user")
                                    .header(AuthHeaders.USER_ID, "DO_NOT_ECHO_INVALID_HEADER"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.userId").value(CUSTOM_USER.userId().toString()))
                            .andExpect(jsonPath("$.role").value("MASTER"));
                });
    }

    @Test
    void applicationProvidedProviderTakesPrecedenceAndFeedsTheMvcResolver() {
        discovery.withUserConfiguration(CustomProviderConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CurrentUserProvider.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();

            mvc.perform(get("/test/current-user")
                            .requestAttr(RequestAttributeCurrentUserProvider.CURRENT_USER_ATTRIBUTE, STORED_USER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(CUSTOM_USER.userId().toString()))
                    .andExpect(jsonPath("$.role").value("MASTER"));
        });
    }

    @Test
    void applicationProvidedResolverTakesPrecedenceAndIsRegisteredWithMvc() {
        discovery.withUserConfiguration(CustomResolverConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CurrentUserArgumentResolver.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();

            mvc.perform(get("/test/current-user")
                            .requestAttr(RequestAttributeCurrentUserProvider.CURRENT_USER_ATTRIBUTE, STORED_USER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(CUSTOM_USER.userId().toString()))
                    .andExpect(jsonPath("$.role").value("MASTER"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "true"})
    void unrelatedMvcConfigurerAndAuthenticationSupportWorkTogether(String enabled) {
        discovery.withUserConfiguration(CorsConfiguration.class)
                .withPropertyValues("travelpay.auth.header-context.enabled=" + enabled).run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();

            mvc.perform(get("/test/current-user")
                            .requestAttr(RequestAttributeCurrentUserProvider.CURRENT_USER_ATTRIBUTE, STORED_USER)
                            .header(HttpHeaders.ORIGIN, "https://example.test"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://example.test"))
                    .andExpect(jsonPath("$.userId").value(STORED_USER.userId().toString()));
        });
    }

    @Test
    void applicationCanExplicitlyExcludeTheAuthenticationAutoConfiguration() {
        discovery.withPropertyValues(HEADER_CONTEXT_ENABLED,
                        "spring.autoconfigure.exclude=" + CommonAuthWebAutoConfiguration.class.getName())
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(CurrentUserProvider.class)
                        .doesNotHaveBean("currentUserArgumentResolver")
                        .doesNotHaveBean("commonAuthWebMvcConfigurer")
                        .doesNotHaveBean("currentUserHeaderInterceptor")
                        .doesNotHaveBean("commonAuthHeaderWebMvcConfigurer"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ProbeController.class)
    static class DiscoveryApplication {
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomProviderConfiguration {

        @Bean
        CurrentUserProvider customCurrentUserProvider() {
            return () -> Optional.of(CUSTOM_USER);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomResolverConfiguration {

        @Bean
        CurrentUserArgumentResolver customCurrentUserArgumentResolver() {
            return new CurrentUserArgumentResolver(() -> Optional.of(CUSTOM_USER));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomHeaderInterceptorConfiguration {

        @Bean
        CurrentUserHeaderInterceptor applicationHeaderInterceptor() {
            return new CurrentUserHeaderInterceptor() {
                @Override
                public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                    request.setAttribute(RequestAttributeCurrentUserProvider.CURRENT_USER_ATTRIBUTE, CUSTOM_USER);
                    return true;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CorsConfiguration {

        @Bean
        WebMvcConfigurer corsConfigurer() {
            return new WebMvcConfigurer() {
                @Override
                public void addCorsMappings(CorsRegistry registry) {
                    registry.addMapping("/test/**").allowedOrigins("https://example.test");
                }
            };
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/test/current-user")
        AuthenticatedUser currentUser(@CurrentUser AuthenticatedUser user) {
            return user;
        }

        @GetMapping("/test/public")
        String publicEndpoint() {
            return "public";
        }
    }

    @RestController
    static class ProviderProbeController {

        private final CurrentUserProvider provider;

        ProviderProbeController(CurrentUserProvider provider) {
            this.provider = provider;
        }

        @GetMapping("/test/provider-user")
        AuthenticatedUser currentUser() {
            return provider.requireCurrentUser();
        }
    }
}
