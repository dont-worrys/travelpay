package com.travelpay.exchange.infrastructure.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.infrastructure.persistence.quote.QuoteRedisConnection;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslOptions;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 견적 전용 Redis 연결이 기본 연결과 같은 TLS 설정을 따르는지 확인합니다(#261).
 * 견적 연결은 직접 만들기 때문에, TLS를 빠뜨리면 Redis에 TLS를 켜는 순간 환율 캐시는 되고 견적만 503이 됩니다.
 * 연결 설정만 보고 Redis에 접속하지는 않습니다(연결은 첫 명령 때 맺음).
 */
class QuoteRedisConfigurationTest {

    private static final Duration QUOTE_TIMEOUT = Duration.ofSeconds(1);

    private final ClientResources clientResources = DefaultClientResources.create();
    private QuoteRedisConnection connection;

    @AfterEach
    void tearDown() {
        if (connection != null) {
            connection.destroy();
        }
        clientResources.shutdown();
    }

    @Test
    void usesPlainConnectionWhenTlsIsOff() {
        LettuceClientConfiguration client = create(null, new DataRedisProperties());

        assertFalse(client.isUseSsl());
        assertEquals(QUOTE_TIMEOUT, client.getCommandTimeout());
    }

    /** spring.data.redis.ssl.enabled=true: 인증서 묶음(SslBundle)의 암호 방식·프로토콜까지 견적 연결에 옮깁니다. */
    @Test
    void usesTlsAndBundleOptionsWhenSslIsEnabled() {
        SslBundle bundle = SslBundle.of(SslStoreBundle.NONE, null,
                SslOptions.of(new String[] {"TLS_AES_128_GCM_SHA256"}, new String[] {"TLSv1.3"}));

        LettuceClientConfiguration client = create(bundle, new DataRedisProperties());

        assertTrue(client.isUseSsl());
        io.lettuce.core.SslOptions ssl = client.getClientOptions().orElseThrow().getSslOptions();
        assertArrayEquals(new String[] {"TLS_AES_128_GCM_SHA256"}, ssl.getCipherSuites());
        assertArrayEquals(new String[] {"TLSv1.3"}, ssl.getProtocols());
        // TLS를 켜도 명령 대기 한도는 견적용 그대로
        assertEquals(QUOTE_TIMEOUT, client.getCommandTimeout());
    }

    /** spring.data.redis.url이 rediss://면 인증서 묶음 없이도 TLS로 붙습니다(Spring Boot 기본 연결과 같음). */
    @Test
    void usesTlsWhenUrlSchemeIsRediss() {
        DataRedisProperties properties = new DataRedisProperties();
        properties.setUrl("rediss://localhost:6380");

        LettuceClientConfiguration client = create(null, properties);

        assertTrue(client.isUseSsl());
        assertEquals(QUOTE_TIMEOUT, client.getCommandTimeout());
    }

    private LettuceClientConfiguration create(SslBundle sslBundle, DataRedisProperties properties) {
        DataRedisConnectionDetails details = new DataRedisConnectionDetails() {
            @Override
            public Standalone getStandalone() {
                return Standalone.of("localhost", 6380);
            }

            @Override
            public SslBundle getSslBundle() {
                return sslBundle;
            }
        };
        QuoteProperties quoteProperties = new QuoteProperties(Duration.ofMinutes(5), new BigDecimal("0.01"),
                Map.of(Currency.KRW, new QuoteProperties.Limit(BigDecimal.ONE, BigDecimal.TEN)), QUOTE_TIMEOUT);
        connection = new QuoteRedisConfiguration().quoteRedisConnection(details, properties, quoteProperties,
                clientResources, new StaticListableBeanFactory().getBeanProvider(LettuceClientOptionsBuilderCustomizer.class));
        return connection.connectionFactory().getClientConfiguration();
    }
}
