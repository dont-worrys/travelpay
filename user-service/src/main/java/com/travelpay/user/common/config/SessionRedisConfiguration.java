package com.travelpay.user.common.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.travelpay.user.common.security.session.RedisSessionSerializer;
import com.travelpay.user.common.security.session.UserSessionPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.MapSession;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.data.redis.RedisSessionMapper;
import org.springframework.session.data.redis.RedisSessionRepository;

/** 손상되었거나 절대 만료가 지난 세션을 없는 세션으로 정리해 CSRF 조회·재로그인을 가능하게 합니다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UserSessionProperties.class)
public class SessionRedisConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SessionRedisConfiguration.class);

    @Bean
    RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return new RedisSessionSerializer();
    }

    @Bean
    SessionRepositoryCustomizer<RedisSessionRepository> sessionMapperCustomizer(Clock clock,
            UserSessionProperties properties) {
        var mapper = new RedisSessionMapper();
        return repository -> repository.setRedisSessionMapper((sessionId, attributes) -> {
            if (attributes.values().stream().anyMatch(RedisSessionSerializer::isUnreadable)) {
                return null;
            }
            try {
                var session = mapper.apply(sessionId, attributes);
                return isPastAbsoluteTimeout(session, clock.instant(), properties.absoluteTimeout()) ? null : session;
            }
            catch (IllegalStateException | ClassCastException exception) {
                // 세션 ID는 로그인 수단이라 남기지 않고 원인 종류만 기록합니다.
                log.warn("형식이 맞지 않는 세션을 없는 세션으로 처리합니다: {}", exception.getClass().getSimpleName());
                return null;
            }
        });
    }

    /** 로그인 세션만 절대 만료를 검사합니다. CSRF만 담긴 익명 세션에는 유휴 만료만 적용됩니다. */
    private static boolean isPastAbsoluteTimeout(MapSession session, Instant now, Duration absoluteTimeout) {
        return session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                instanceof SecurityContext context
                && context.getAuthentication() != null
                && context.getAuthentication().getPrincipal() instanceof UserSessionPrincipal principal
                && principal.isExpiredAt(now, absoluteTimeout);
    }
}
