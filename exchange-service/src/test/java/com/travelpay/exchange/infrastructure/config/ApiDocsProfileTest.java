package com.travelpay.exchange.infrastructure.config;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** API 문서(Swagger)는 로컬 설정에서만 켜고 운영 설정에서는 끈다(#233). 운영에 문서가 열리지 않게 설정 파일을 직접 확인한다. */
class ApiDocsProfileTest {

    @Test
    void apiDocsAreOnLocallyAndOffInProduction() {
        Properties local = load("application.yml");
        Properties prod = load("application-prod.yml");

        assertEquals("true", local.getProperty("springdoc.api-docs.enabled"));
        assertEquals("true", local.getProperty("springdoc.swagger-ui.enabled"));
        assertEquals("false", prod.getProperty("springdoc.api-docs.enabled"));
        assertEquals("false", prod.getProperty("springdoc.swagger-ui.enabled"));
    }

    private static Properties load(String file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(file));
        return yaml.getObject();
    }
}
