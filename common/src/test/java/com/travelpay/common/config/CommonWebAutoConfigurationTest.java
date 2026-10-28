package com.travelpay.common.config;

import com.travelpay.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class CommonWebAutoConfigurationTest {

    private final WebApplicationContextRunner servlet = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class));

    @Test
    void servletApplicationGetsOneDefaultHandler() {
        servlet.run(context -> assertThat(context).hasSingleBean(GlobalExceptionHandler.class));
    }

    @Test
    void applicationProvidedHandlerTakesPrecedence() {
        servlet.withUserConfiguration(CustomHandlerConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
            assertThat(context.getBean(GlobalExceptionHandler.class)).isInstanceOf(CustomHandler.class);
        });
    }

    @Test
    void reactiveApplicationDoesNotRegisterTheServletHandler() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(GlobalExceptionHandler.class));
    }

    @Test
    void nonWebApplicationDoesNotRegisterTheServletHandler() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(GlobalExceptionHandler.class));
    }

    @Test
    void missingMvcClassesDoNotBreakStartup() {
        servlet.withClassLoader(new FilteredClassLoader("org.springframework.web.servlet"))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("globalExceptionHandler"));
    }

    @Test
    void bootDiscoversTheHandlerThroughTheImportsResource() {
        new WebApplicationContextRunner().withUserConfiguration(DiscoveryApplication.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(GlobalExceptionHandler.class));
    }

    @Test
    void applicationCanExplicitlyExcludeTheAutoConfiguration() {
        new WebApplicationContextRunner().withUserConfiguration(DiscoveryApplication.class)
                .withPropertyValues("spring.autoconfigure.exclude=com.travelpay.common.config.CommonWebAutoConfiguration")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(GlobalExceptionHandler.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomHandlerConfiguration {

        @Bean
        CustomHandler customHandler() {
            return new CustomHandler();
        }
    }

    static class CustomHandler extends GlobalExceptionHandler {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class DiscoveryApplication {
    }
}
