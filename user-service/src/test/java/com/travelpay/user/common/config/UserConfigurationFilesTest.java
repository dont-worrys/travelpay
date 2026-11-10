package com.travelpay.user.common.config;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 로컬·배포 설정 파일의 보안 관련 기본값과 실행 환경의 조정 가능 여부를 확인합니다. */
class UserConfigurationFilesTest {

    @Test
    void localAndProdBindTheSameRequestHeaderBudget() throws IOException {
        var local = serverProperties("application.yml");
        var prod = serverProperties("application-prod.yml");

        assertEquals(local.getMaxHttpRequestHeaderSize(), prod.getMaxHttpRequestHeaderSize());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void passwordChangeUsesTheBoundedDatabaseTransactionTimeout(String filename) throws IOException {
        var properties = Binder.get(environment(filename))
                .bind("travelpay.user.password-change", PasswordChangeProperties.class).get();

        assertEquals(Duration.ofSeconds(5), properties.transactionTimeout());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void passwordChangeTransactionTimeoutCanBeOverriddenByTheEnvironment(String filename) throws IOException {
        var environment = environment(filename)
                .withProperty("USER_PASSWORD_CHANGE_TRANSACTION_TIMEOUT", "3s");
        var properties = Binder.get(environment)
                .bind("travelpay.user.password-change", PasswordChangeProperties.class).get();

        assertEquals(Duration.ofSeconds(3), properties.transactionTimeout());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void adminApisUseTheBoundedDatabaseTransactionTimeout(String filename) throws IOException {
        var properties = Binder.get(environment(filename))
                .bind("travelpay.user.admin", AdminProperties.class).get();

        assertEquals(Duration.ofSeconds(5), properties.transactionTimeout());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void adminTransactionTimeoutCanBeOverriddenByTheEnvironment(String filename) throws IOException {
        var environment = environment(filename).withProperty("USER_ADMIN_TRANSACTION_TIMEOUT", "3s");
        var properties = Binder.get(environment).bind("travelpay.user.admin", AdminProperties.class).get();

        assertEquals(Duration.ofSeconds(3), properties.transactionTimeout());
    }

    private static ServerProperties serverProperties(String filename) throws IOException {
        return Binder.get(environment(filename)).bindOrCreate("server", ServerProperties.class);
    }

    private static MockEnvironment environment(String filename) throws IOException {
        var environment = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader().load(filename, new ClassPathResource(filename))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }
}
