package com.travelpay.eureka;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.cloud.commons.util.InetUtilsProperties;
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean;
import org.springframework.cloud.netflix.eureka.EurekaInstanceConfigBean;
import org.springframework.cloud.netflix.eureka.TimeoutProperties;
import org.springframework.cloud.netflix.eureka.http.DefaultEurekaClientHttpRequestFactorySupplier;
import org.springframework.cloud.netflix.eureka.http.RestClientDiscoveryClientOptionalArgs;
import org.springframework.cloud.netflix.eureka.http.RestClientTransportClientFactories;
import org.springframework.web.client.RestClient;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 HTTP로 최초 등록·등록 유실 후 복구·OUT_OF_SERVICE 반영을 검사합니다. 주기는 시험에서만 단축합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        args = {"--spring.config.name=application-prod", "--spring.profiles.active=prod"}, properties = {
        "server.address=127.0.0.1",
        "eureka.instance.hostname=127.0.0.1",
        "eureka.client.service-url.defaultZone=http://127.0.0.1:8761/eureka/",
        "eureka.server.enable-self-preservation=false",
        "eureka.server.use-read-only-response-cache=false",
        "logging.level.root=WARN"
})
class EurekaRecoveryIntegrationTest {

    private static final String APP = "RECOVERY-TEST";
    private static final String INSTANCE = "recovery-test-1";

    @Autowired
    private PeerAwareInstanceRegistry registry;

    @Value("${local.server.port}")
    private int port;

    @Test
    @Timeout(45)
    void clientRecoversLostRegistrationAndHonorsOutOfService() throws Exception {
        try (InetUtils inetUtils = new InetUtils(new InetUtilsProperties())) {
            DiscoveryClient client = newClient(inetUtils);
            try {
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                    assertNotNull(client.getApplication(APP));
                    assertEquals(1, client.getApplication(APP).getInstances().size());
                });
                assertNull(client.getApplication("MISSING-SERVICE"));

                long originalRegistration = registry.getInstanceByAppAndId(APP, INSTANCE)
                        .getLeaseInfo().getRegistrationTimestamp();
                assertTrue(registry.cancel(APP, INSTANCE, false));

                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                    InstanceInfo recovered = registry.getInstanceByAppAndId(APP, INSTANCE);
                    assertNotNull(recovered);
                    assertEquals(InstanceInfo.InstanceStatus.UP, recovered.getStatus());
                    assertTrue(recovered.getLeaseInfo().getRegistrationTimestamp() > originalRegistration);
                });

                // 시작 스크립트가 사용하는 상태 변경 API를 호출합니다. 앱이 살아 있어도 목록에서는 빠져야 합니다.
                try (HttpClient http = HttpClient.newHttpClient()) {
                    var request = HttpRequest.newBuilder(URI.create(baseUrl() + "apps/" + APP + "/" + INSTANCE
                                    + "/status?value=OUT_OF_SERVICE"))
                            .timeout(Duration.ofSeconds(3))
                            .PUT(HttpRequest.BodyPublishers.noBody()).build();
                    assertEquals(200, http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
                }
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                    var application = client.getApplication(APP);
                    assertTrue(application == null || application.getInstances().isEmpty());
                });
            } finally {
                client.shutdown();
            }
        }
    }

    private DiscoveryClient newClient(InetUtils inetUtils) {
        var instance = new EurekaInstanceConfigBean(inetUtils);
        instance.setAppname(APP);
        instance.setVirtualHostName(APP.toLowerCase(java.util.Locale.ROOT));
        instance.setInstanceId(INSTANCE);
        instance.setHostname("127.0.0.1");
        instance.setIpAddress("127.0.0.1");
        instance.setNonSecurePort(8081);
        instance.setLeaseRenewalIntervalInSeconds(1);
        var manager = new ApplicationInfoManager(instance, new EurekaConfigBasedInstanceInfoProvider(instance).get());
        manager.setInstanceStatus(InstanceInfo.InstanceStatus.UP);

        var config = new EurekaClientConfigBean();
        config.setServiceUrl(Map.of("defaultZone", baseUrl()));
        config.setRegistryFetchIntervalSeconds(1);
        config.setInitialInstanceInfoReplicationIntervalSeconds(0);
        config.setShouldEnforceRegistrationAtInit(true);

        var timeouts = new TimeoutProperties();
        timeouts.setConnectTimeout(2000);
        timeouts.setConnectRequestTimeout(2000);
        timeouts.setSocketTimeout(2000);
        var optionalArgs = new RestClientDiscoveryClientOptionalArgs(
                new DefaultEurekaClientHttpRequestFactorySupplier(timeouts, Set.of()), RestClient::builder);
        return new DiscoveryClient(manager, config, new RestClientTransportClientFactories(optionalArgs), optionalArgs);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port + "/eureka/";
    }
}
