package com.travelpay.payment.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 실제 PostgreSQL이 필요한 payment-service 통합 테스트의 공통 상위 클래스입니다.
 * Testcontainers로 PostgreSQL 컨테이너를 띄우고 Flyway 마이그레이션을 적용한 스키마(payment_service)를 씁니다.
 * 컨테이너는 테스트 JVM에서 한 번만 띄워 여러 테스트 클래스가 함께 쓰며(스프링 컨텍스트 캐시도 유지), JVM 종료 시 정리됩니다.
 * Docker가 없으면 테스트를 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresContainerSupport {

    private static final String SCHEMA = "payment_service";

    // compose(.env.example POSTGRES_IMAGE)와 같은 이미지입니다.
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static synchronized void start() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }
}
