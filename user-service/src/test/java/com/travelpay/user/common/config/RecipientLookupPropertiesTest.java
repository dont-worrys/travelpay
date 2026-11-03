package com.travelpay.user.common.config;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecipientLookupPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void environmentConfigurationUsesSixtyLookupsInSixtySeconds(String filename) throws IOException {
        runner(filename).run(context -> {
            assertNull(context.getStartupFailure());
            var properties = context.getBean(RecipientLookupProperties.class);

            assertEquals(60, properties.limit());
            assertEquals(Duration.ofSeconds(60), properties.period());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void environmentOverridesChangeBothLimits(String filename) throws IOException {
        runner(filename).withPropertyValues("USER_RECIPIENT_LOOKUP_LIMIT=45",
                "USER_RECIPIENT_LOOKUP_PERIOD=2m").run(context -> {
            assertNull(context.getStartupFailure());
            var properties = context.getBean(RecipientLookupProperties.class);

            assertEquals(45, properties.limit());
            assertEquals(Duration.ofMinutes(2), properties.period());
        });
    }

    @Test
    void oneLookupAndOneSecondAreAcceptedAsTheMinimumConfiguration() throws IOException {
        runner("application.yml").withPropertyValues("travelpay.user.recipient-lookup.limit=1",
                "travelpay.user.recipient-lookup.period=1s").run(context -> {
            assertNull(context.getStartupFailure());
            var properties = context.getBean(RecipientLookupProperties.class);

            assertEquals(1, properties.limit());
            assertEquals(Duration.ofSeconds(1), properties.period());
        });
    }

    @ParameterizedTest
    @CsvSource({"limit=45, 45, PT1M", "period=2m, 60, PT2M"})
    void aPartialOverrideKeepsTheOtherDefault(String property, int expectedLimit, Duration expectedPeriod)
            throws IOException {
        runner("application.yml").withPropertyValues("travelpay.user.recipient-lookup." + property).run(context -> {
            assertNull(context.getStartupFailure());
            var properties = context.getBean(RecipientLookupProperties.class);

            assertEquals(expectedLimit, properties.limit());
            assertEquals(expectedPeriod, properties.period());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "limit=0", "limit=-1", "limit=",
            "period=0s", "period=-1s", "period=999ms", "period=60", "period=",
            "period=PT9223372036854775807S", "period=9223372036854775807ms", "period=9007199254740993ms"
    })
    void invalidConfigurationStopsStartupInsteadOfSilentlyUsingDefaults(String property) throws IOException {
        runner("application.yml").withPropertyValues("travelpay.user.recipient-lookup." + property).run(context -> {
            var failure = context.getStartupFailure();

            assertNotNull(failure);
            assertInstanceOf(IllegalArgumentException.class, NestedExceptionUtils.getMostSpecificCause(failure));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER_RECIPIENT_LOOKUP_LIMIT", "USER_RECIPIENT_LOOKUP_PERIOD"})
    void anExplicitlyEmptyEnvironmentValueStopsStartup(String name) throws IOException {
        runner("application.yml").withPropertyValues(name + "=").run(context -> {
            var failure = context.getStartupFailure();

            assertNotNull(failure);
            assertInstanceOf(IllegalArgumentException.class, NestedExceptionUtils.getMostSpecificCause(failure));
        });
    }

    @Test
    void aMissingPeriodCannotBeConstructedDirectly() {
        assertThrows(IllegalArgumentException.class, () -> new RecipientLookupProperties(60, null));
    }

    private ApplicationContextRunner runner(String filename) throws IOException {
        var sources = new YamlPropertySourceLoader().load(filename, new ClassPathResource(filename));
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    var propertySources = context.getEnvironment().getPropertySources();
                    propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    sources.forEach(propertySources::addLast);
                })
                .withUserConfiguration(PropertiesConfiguration.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RecipientLookupProperties.class)
    static class PropertiesConfiguration {
    }
}
