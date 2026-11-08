package com.travelpay.user.application.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorCode;
import com.travelpay.user.application.dto.AdminTermsListCommand;
import com.travelpay.user.application.dto.AdminTermsPublishCommand;
import com.travelpay.user.application.dto.TermsResult;
import com.travelpay.user.common.config.JpaAuditingConfiguration;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.repository.TermsDocumentRepository;
import com.travelpay.user.infrastructure.persistence.JpaRepositoryTestConfiguration;
import com.travelpay.user.infrastructure.persistence.PostgresJpaTestConfiguration;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 관리자 약관 조회·등록을 실제 PostgreSQL 트랜잭션과 고정한 서버 시각으로 확인합니다. */
@SpringJUnitConfig(classes = {
        JpaAuditingConfiguration.class, PostgresJpaTestConfiguration.class, JpaRepositoryTestConfiguration.class,
        AdminTermsService.class, AdminTermsServiceIntegrationTest.TestBeans.class
})
@TestPropertySource(properties = "travelpay.user.admin.transaction-timeout=2s")
class AdminTermsServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Autowired private AdminTermsService service;
    @Autowired private TermsDocumentRepository termsRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ListAppender<ILoggingEvent> audit = new ListAppender<>();
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        transactions.executeWithoutResult(status -> {
            for (var entity : List.of("UserConsent", "TermsDocument")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
        });
    }

    @Test
    void aPublishedFutureVersionIsListedButTheCurrentTermsStayTheSameUntilItTakesEffect() {
        service.publish(ACTOR, command("SERVICE_TERMS", "v1", "원래 약관", NOW.plusSeconds(60)));
        var current = transactions.execute(status -> termsRepository.findEffectiveAt(NOW.plusSeconds(120)));

        var published = service.publish(ACTOR, command("SERVICE_TERMS", "v2", "새 약관", NOW.plusSeconds(86_400)));

        assertEquals("v2", published.termsVersion());
        assertEquals(List.of("v2", "v1"), versions(listed("SERVICE_TERMS")));
        assertEquals(current.stream().map(terms -> terms.getId().termsVersion()).toList(),
                transactions.execute(status -> termsRepository.findEffectiveAt(NOW.plusSeconds(120)))
                        .stream().map(terms -> terms.getId().termsVersion()).toList());
    }

    @ParameterizedTest
    @ValueSource(longs = {59, 0, -1, -86_400})
    void aVersionThatWouldTakeEffectWithinAMinuteOrInThePastIsRejected(long secondsFromNow) {
        assertError(UserErrorCode.TERMS_EFFECTIVE_AT_NOT_FUTURE,
                () -> service.publish(ACTOR, command("SERVICE_TERMS", "v1", "약관", NOW.plusSeconds(secondsFromNow))));

        assertTrue(listed(null).isEmpty());
    }

    @Test
    void aDuplicateVersionOrEffectiveTimeIsRejectedAndThePublishedVersionStaysTheSame() {
        var effectiveAt = NOW.plusSeconds(60);
        service.publish(ACTOR, command("SERVICE_TERMS", "v1", "원래 약관", effectiveAt));

        assertError(UserErrorCode.TERMS_VERSION_CONFLICT,
                () -> service.publish(ACTOR, command("SERVICE_TERMS", "v1", "바꾼 약관", NOW.plusSeconds(120))));
        assertError(UserErrorCode.TERMS_VERSION_CONFLICT,
                () -> service.publish(ACTOR, command("SERVICE_TERMS", "v2", "바꾼 약관", effectiveAt)));

        var stored = listed("SERVICE_TERMS");
        assertEquals(1, stored.size());
        assertEquals("원래 약관", stored.getFirst().title());
        assertEquals(effectiveAt, stored.getFirst().effectiveAt());
    }

    @Test
    void twoAdminsPublishingTheSameVersionTogetherStoreItOnce() throws Exception {
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> publishAfter(start, "첫 번째 약관"));
            var second = executor.submit(() -> publishAfter(start, "두 번째 약관"));

            // 성공은 null로 표시하므로 null을 받는 목록을 씁니다.
            var outcomes = Arrays.asList(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

            assertEquals(1, outcomes.stream().filter(outcome -> outcome == null).count());
            assertTrue(outcomes.contains(UserErrorCode.TERMS_VERSION_CONFLICT));
        }
        assertEquals(1, listed("SERVICE_TERMS").size());
    }

    // +300000년은 PostgreSQL이 저장할 수 없는 시각이라, 막지 않으면 저장 장애(503)가 됩니다.
    @ParameterizedTest
    @ValueSource(strings = {"+10000-01-01T00:00:00Z", "+300000-01-01T00:00:00Z"})
    void anEffectiveTimeAfterTheYear9999IsABadRequestNotAnOutage(String effectiveAt) {
        assertError(CommonErrorCode.INVALID_REQUEST,
                () -> service.publish(ACTOR, command("SERVICE_TERMS", "v1", "약관", Instant.parse(effectiveAt))));

        assertTrue(listed(null).isEmpty());
    }

    @Test
    void theLastMicrosecondOfTheYear9999IsStoredAsIs() {
        var latest = Instant.parse("9999-12-31T23:59:59.999999Z");

        service.publish(ACTOR, command("SERVICE_TERMS", "v1", "약관", latest));

        assertEquals(latest, listed("SERVICE_TERMS").getFirst().effectiveAt());
    }

    @Test
    void theEffectiveTimeIsKeptToTheMicrosecondTheDatabaseStores() {
        var requested = NOW.plusSeconds(60).plusNanos(123_456_789);

        var published = service.publish(ACTOR, command("SERVICE_TERMS", "v1", "약관", requested));

        var stored = NOW.plusSeconds(60).plusNanos(123_456_000);
        assertEquals(stored, published.effectiveAt());
        assertEquals(stored, listed("SERVICE_TERMS").getFirst().effectiveAt());
    }

    @Test
    void aTitleThatBreaksTheTermsRulesIsReportedAsABadRequest() {
        assertError(CommonErrorCode.INVALID_REQUEST,
                () -> service.publish(ACTOR, command("SERVICE_TERMS", "v1", "제목\n줄바꿈", NOW.plusSeconds(60))));
    }

    @Test
    void listingShowsCodesInOrderWithTheLatestVersionFirstAndReportsTheNextPage() {
        service.publish(ACTOR, command("SERVICE_TERMS", "v1", "약관", NOW.plusSeconds(60)));
        service.publish(ACTOR, command("SERVICE_TERMS", "v2", "약관", NOW.plusSeconds(120)));
        service.publish(ACTOR, command("PRIVACY_TERMS", "v1", "약관", NOW.plusSeconds(60)));

        var first = service.list(new AdminTermsListCommand(null, 0, 2));
        var second = service.list(new AdminTermsListCommand(" ", 1, 2));

        assertEquals(List.of("PRIVACY_TERMS v1", "SERVICE_TERMS v2"), labels(first.items()));
        assertTrue(first.hasNext());
        assertEquals(List.of("SERVICE_TERMS v1"), labels(second.items()));
        assertFalse(second.hasNext());
    }

    @Test
    void aPublishedVersionIsAuditedAndARejectedOneIsNot() {
        var effectiveAt = NOW.plusSeconds(60);
        service.publish(ACTOR, command("SERVICE_TERMS", "v1", "약관", effectiveAt));
        assertError(UserErrorCode.TERMS_VERSION_CONFLICT,
                () -> service.publish(ACTOR, command("SERVICE_TERMS", "v1", "약관", NOW.plusSeconds(120))));

        assertEquals(List.of(Map.of("event", "ADMIN_TERMS_PUBLISHED", "actorId", ACTOR.toString(),
                "termsCode", "SERVICE_TERMS", "termsVersion", "v1", "effectiveAt", effectiveAt.toString())),
                auditEvents());
    }

    @BeforeEach
    void captureAuditLog() {
        audit.start();
        ((Logger) LoggerFactory.getLogger("travelpay.audit.admin")).addAppender(audit);
    }

    @AfterEach
    void releaseAuditLog() {
        ((Logger) LoggerFactory.getLogger("travelpay.audit.admin")).detachAppender(audit);
    }

    private List<Map<String, Object>> auditEvents() {
        return audit.list.stream().map(event -> event.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value))).toList();
    }

    private ErrorCode publishAfter(CyclicBarrier start, String title) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            service.publish(ACTOR, command("SERVICE_TERMS", "v1", title, NOW.plusSeconds(60)));
            return null;
        }
        catch (BusinessException exception) {
            return exception.getErrorCode();
        }
    }

    private List<TermsResult> listed(String termsCode) {
        return service.list(new AdminTermsListCommand(termsCode, 0, 20)).items();
    }

    private static AdminTermsPublishCommand command(String code, String version, String title, Instant effectiveAt) {
        return new AdminTermsPublishCommand(code, version, title, "약관 본문", true, effectiveAt);
    }

    private static List<String> versions(List<TermsResult> items) {
        return items.stream().map(TermsResult::termsVersion).toList();
    }

    private static List<String> labels(List<TermsResult> items) {
        return items.stream().map(item -> item.termsCode() + " " + item.termsVersion()).toList();
    }

    private static void assertError(ErrorCode expected, Runnable call) {
        assertEquals(expected, assertThrows(BusinessException.class, call::run).getErrorCode());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
