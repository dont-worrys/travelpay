package com.travelpay.user.infrastructure.persistence;

import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.travelpay.user.domain.model.entity.User;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * User의 DB 연동 테스트가 공유하는 PostgreSQL·JPA 실행 설정입니다.
 * 실제 앱 컴포넌트 스캔에 섞여 application.yml의 DB 설정을 대신하지 않도록 TestConfiguration으로 둡니다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresJpaTestConfiguration {

    @Bean(destroyMethod = "stop")
    PostgreSQLContainer postgres() {
        var postgres = new PostgreSQLContainer("postgres:18.6-bookworm")
                .withDatabaseName("travelpay_user_test")
                .withUsername("travelpay_test")
                .withPassword(UUID.randomUUID().toString())
                .withInitScript("postgres/user-schema.sql");
        postgres.start();
        return postgres;
    }

    @Bean
    DataSource dataSource(PostgreSQLContainer postgres) {
        return new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(User.class.getPackageName());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.default_schema", "user_service"));
        return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
