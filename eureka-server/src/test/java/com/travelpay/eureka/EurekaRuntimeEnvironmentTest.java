package com.travelpay.eureka;

import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.cloud.commons.util.InetUtilsProperties;
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean;
import org.springframework.cloud.netflix.eureka.EurekaInstanceConfigBean;
import org.springframework.cloud.netflix.eureka.server.EurekaServerConfigBean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EurekaRuntimeEnvironmentTest {

    @Test
    void ecsEnvironmentBindsToActualEurekaPropertiesWithoutShorteningTheLease() {
        var environment = new StandardEnvironment();
        // 일반 테스트 속성이 아니라 ECS와 같은 대문자·밑줄 환경 변수 바인딩을 확인합니다.
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                        "EUREKA_INSTANCE_LEASE_RENEWAL_INTERVAL_IN_SECONDS", "5",
                        "EUREKA_CLIENT_REGISTRY_FETCH_INTERVAL_SECONDS", "5",
                        "EUREKA_SERVER_EXPECTED_CLIENT_RENEWAL_INTERVAL_SECONDS", "5",
                        "EUREKA_SERVER_RESPONSE_CACHE_UPDATE_INTERVAL_MS", "5000",
                        "SPRING_CLOUD_LOADBALANCER_CACHE_ENABLED", "false")));
        var binder = Binder.get(environment);

        try (var inetUtils = new InetUtils(new InetUtilsProperties())) {
            var instance = new EurekaInstanceConfigBean(inetUtils);
            binder.bind("eureka.instance", Bindable.ofInstance(instance));
            var client = binder.bindOrCreate("eureka.client", EurekaClientConfigBean.class);
            var server = binder.bindOrCreate("eureka.server", EurekaServerConfigBean.class);

            assertEquals(5, instance.getLeaseRenewalIntervalInSeconds());
            assertEquals(90, instance.getLeaseExpirationDurationInSeconds());
            assertEquals(5, new EurekaConfigBasedInstanceInfoProvider(instance).get()
                    .getLeaseInfo().getRenewalIntervalInSecs());
            assertEquals(5, client.getRegistryFetchIntervalSeconds());
            assertEquals(5, server.getExpectedClientRenewalIntervalSeconds());
            assertEquals(5000, server.getResponseCacheUpdateIntervalMs());
            assertEquals(Boolean.FALSE,
                    environment.getProperty("spring.cloud.loadbalancer.cache.enabled", Boolean.class));
        }
    }
}
