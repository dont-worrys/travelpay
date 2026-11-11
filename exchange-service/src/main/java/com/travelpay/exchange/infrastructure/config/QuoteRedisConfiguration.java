package com.travelpay.exchange.infrastructure.config;

import java.net.URI;
import java.time.Duration;

import com.travelpay.exchange.infrastructure.persistence.quote.QuoteRedisConnection;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.resource.ClientResources;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.StringUtils;

/**
 * 견적 저장소 전용 Redis 연결을 만듭니다(#261). 환율 캐시용 기본 연결은 Spring Boot가 spring.data.redis로 만든 그대로 둡니다.
 *
 * 주소·계정·연결 대기(connect-timeout)·TLS는 기본 연결과 같은 값(spring.data.redis)을 쓰고, 명령 대기 한도만 exchange.quote.redis-timeout으로 다르게 합니다.
 * Lettuce 옵션도 기본 연결과 맞춥니다.
 * - 연결 준비(인증 등)에도 명령 대기 한도를 적용(TimeoutOptions): Spring Boot 기본 연결과 같은 동작
 * - 끊긴 동안 명령을 바로 거절(RedisClientConfiguration): 끊긴 동안 기다리지 않고 바로 503
 * - TLS: spring.data.redis.ssl.enabled(인증서 묶음 포함)나 rediss:// 주소면 견적 연결도 TLS로 붙습니다.
 *   Spring Boot가 기본 연결에 적용하는 규칙과 같습니다. 빠지면 TLS만 받는 Redis가 견적 연결을 거절해 견적이 모두 503이 됩니다
 * 스레드 등 Lettuce 자원(ClientResources)은 기본 연결과 함께 씁니다.
 *
 * 단일 Redis(standalone)만 지원합니다. 팀 compose·배포 모두 redis-cache 한 대입니다.
 */
@Configuration(proxyBeanMethods = false)
public class QuoteRedisConfiguration {

    @Bean(destroyMethod = "destroy")
    QuoteRedisConnection quoteRedisConnection(DataRedisConnectionDetails connection, DataRedisProperties redisProperties,
                                              QuoteProperties quoteProperties, ClientResources clientResources,
                                              ObjectProvider<LettuceClientOptionsBuilderCustomizer> optionCustomizers) {
        DataRedisConnectionDetails.Standalone server = connection.getStandalone();
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(server.getHost(), server.getPort());
        standalone.setDatabase(server.getDatabase());
        standalone.setUsername(connection.getUsername());
        standalone.setPassword(RedisPassword.of(connection.getPassword()));

        SslBundle sslBundle = connection.getSslBundle();
        ClientOptions.Builder options = ClientOptions.builder().timeoutOptions(TimeoutOptions.enabled());
        Duration connectTimeout = redisProperties.getConnectTimeout();
        if (connectTimeout != null) {
            options.socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build());
        }
        if (sslBundle != null) {
            options.sslOptions(lettuceSslOptions(sslBundle));
        }
        optionCustomizers.orderedStream().forEach(customizer -> customizer.customize(options));

        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder();
        if (sslBundle != null || urlUsesSsl(redisProperties.getUrl())) {
            client.useSsl();
        }
        client.clientResources(clientResources)
                .clientOptions(options.build())
                .commandTimeout(quoteProperties.redisTimeout());
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, client.build());
        factory.afterPropertiesSet();
        factory.start();
        return new QuoteRedisConnection(factory);
    }

    /** 인증서 묶음의 키·신뢰 저장소와 암호 방식·프로토콜을 Lettuce 설정으로 옮깁니다(Spring Boot 기본 연결과 같은 방식). */
    private static io.lettuce.core.SslOptions lettuceSslOptions(SslBundle bundle) {
        io.lettuce.core.SslOptions.Builder builder = io.lettuce.core.SslOptions.builder()
                .keyManager(bundle.getManagers().getKeyManagerFactory())
                .trustManager(bundle.getManagers().getTrustManagerFactory());
        SslOptions options = bundle.getOptions();
        if (options.getCiphers() != null) {
            builder.cipherSuites(options.getCiphers());
        }
        if (options.getEnabledProtocols() != null) {
            builder.protocols(options.getEnabledProtocols());
        }
        return builder.build();
    }

    /** spring.data.redis.url이 rediss://면 TLS입니다. */
    private static boolean urlUsesSsl(String url) {
        return StringUtils.hasText(url) && "rediss".equals(URI.create(url).getScheme());
    }
}
