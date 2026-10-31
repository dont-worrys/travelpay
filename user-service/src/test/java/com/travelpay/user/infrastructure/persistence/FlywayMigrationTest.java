package com.travelpay.user.infrastructure.persistence;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.travelpay.user.domain.model.entity.TermsDocument;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.vo.TermsDocumentId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway 마이그레이션이 만든 스키마를 엔티티 매핑으로 Hibernate가 만드는 스키마와 비교합니다.
 * 다른 DB 테스트는 Hibernate가 만든 스키마에서 실행되므로, 열과 제약조건이 이름까지 같아야 그 결과가 운영 스키마에도 성립합니다.
 */
class FlywayMigrationTest {

    private static final String MIGRATED = "user_service";
    private static final String FROM_ENTITIES = "entity_schema";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withDatabaseName("travelpay_user_migration_test")
            .withUsername("travelpay_test")
            .withPassword(UUID.randomUUID().toString())
            .withInitScript("postgres/user-schema.sql");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAndCreateTheEntitySchema() {
        POSTGRES.start();
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).defaultSchema(MIGRATED).load().migrate();
        jdbc.execute("create schema " + FROM_ENTITIES);
        createTablesFromEntities(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void migratedColumnsMatchTheColumnsTheEntitiesExpect() {
        assertEquals(columns(FROM_ENTITIES), columns(MIGRATED));
    }

    @Test
    void migratedConstraintsMatchTheEntitiesIncludingTheirNames() {
        assertEquals(constraints(FROM_ENTITIES), constraints(MIGRATED));
    }

    @Test
    void theInitialTermsAreTheRequiredSpecVersionsAndPassTheDomainRules() {
        // SQL로 넣은 값이 도메인 규칙을 우회하지 않았는지 정적 팩토리로 다시 만들어 확인합니다.
        var seeded = jdbc.query("""
                select terms_code, terms_version, title, content, is_required, effective_at
                from user_service.terms_documents order by terms_code
                """, (row, number) -> TermsDocument.publish(
                new TermsDocumentId(row.getString("terms_code"), row.getString("terms_version")),
                row.getString("title"), row.getString("content"), row.getBoolean("is_required"),
                row.getObject("effective_at", OffsetDateTime.class).toInstant()));

        assertEquals(List.of(new TermsDocumentId("PRIVACY_TERMS", "2026-10-01"),
                new TermsDocumentId("SERVICE_TERMS", "2026-10-01")), seeded.stream().map(TermsDocument::getId).toList());
        assertEquals(List.of("개인정보 수집·이용 동의", "서비스 이용약관"),
                seeded.stream().map(TermsDocument::getTitle).toList());
        for (var terms : seeded) {
            assertTrue(terms.isRequired());
            assertEquals(Instant.parse("2026-10-01T00:00:00Z"), terms.getEffectiveAt());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void bothConfigurationsLetFlywayOwnTheSchemaAndHibernateOnlyValidateIt(String filename) throws IOException {
        var settings = new YamlPropertySourceLoader().load(filename, new ClassPathResource(filename)).getFirst();

        assertEquals("user_service", String.valueOf(settings.getProperty("spring.flyway.default-schema")));
        assertEquals("validate", String.valueOf(settings.getProperty("spring.jpa.hibernate.ddl-auto")));
    }

    private static void createTablesFromEntities(DataSource dataSource) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(User.class.getPackageName());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "create",
                "hibernate.default_schema", FROM_ENTITIES));
        factory.afterPropertiesSet();
        factory.destroy();
    }

    private static List<String> columns(String schema) {
        return jdbc.queryForList("""
                select table_name || '.' || column_name || ' ' || data_type
                       || coalesce('(' || character_maximum_length || ')', '')
                       || coalesce(' precision ' || datetime_precision, '')
                       || case when is_nullable = 'NO' then ' not null' else '' end
                from information_schema.columns
                where table_schema = ? and table_name <> 'flyway_schema_history'
                order by table_name, column_name
                """, String.class, schema);
    }

    /** 코드와 테스트가 제약조건을 이름으로 구분하므로, 모든 제약조건을 이름과 정의로 비교합니다. */
    private static List<String> constraints(String schema) {
        return jdbc.queryForList("""
                select rel.relname || ' ' || con.conname || ' '
                       || replace(pg_get_constraintdef(con.oid), ns.nspname || '.', '')
                from pg_constraint con
                join pg_class rel on rel.oid = con.conrelid
                join pg_namespace ns on ns.oid = rel.relnamespace
                where ns.nspname = ? and rel.relname <> 'flyway_schema_history'
                order by 1
                """, String.class, schema);
    }
}
