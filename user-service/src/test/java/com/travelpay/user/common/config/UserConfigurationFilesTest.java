package com.travelpay.user.common.config;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 실제 HTTP 시험으로 확인한 헤더 수신 한도를 배포 설정에서도 동일하게 사용합니다. */
class UserConfigurationFilesTest {

    @Test
    void localAndProdBindTheSameRequestHeaderBudget() throws IOException {
        var local = serverProperties("application.yml");
        var prod = serverProperties("application-prod.yml");

        assertEquals(local.getMaxHttpRequestHeaderSize(), prod.getMaxHttpRequestHeaderSize());
    }

    private static ServerProperties serverProperties(String filename) throws IOException {
        var environment = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader().load(filename, new ClassPathResource(filename))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment).bindOrCreate("server", ServerProperties.class);
    }
}
