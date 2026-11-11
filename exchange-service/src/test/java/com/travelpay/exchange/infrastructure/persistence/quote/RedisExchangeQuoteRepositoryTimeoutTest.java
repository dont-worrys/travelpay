package com.travelpay.exchange.infrastructure.persistence.quote;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.domain.order.OrderFixtures;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.ExchangeQuoteErrorCode;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 견적 저장소 Redis가 잠깐 멈출 때의 동작입니다(#261). CLIENT PAUSE로 Redis가 모든 명령을 잠시 붙잡게 해 순간 지연을 만듭니다.
 * CLIENT PAUSE는 OK를 돌려준 순간부터 멈춤이 걸려 있어, 시간을 짐작해 기다리지 않아도 됩니다.
 *
 * - 대기 한도가 짧으면(200ms, 예전에 환율 캐시와 같이 쓰던 값) 저장이 503이 되지만, Redis는 멈춤이 풀린 뒤 그 명령을 실행해 견적이 남습니다.
 *   #250 측정에서 본 "503인데 저장됨"입니다. 대기 초과는 실패가 아니라 결과를 모르는 것입니다.
 * - 대기 한도가 멈춤보다 길면(1s, 지금 설정) 같은 멈춤을 기다려 저장에 성공합니다.
 *
 * 대기 한도는 정확히 그 시각에 끊기지 않습니다. Docker Desktop(Windows)에서 200ms 한도가 400~500ms 뒤에 끊겼습니다.
 * 응답이 오가는 데도 가끔 수백 ms가 더 걸려, 짧은 한도 확인은 멈춤을 넉넉히(2s) 두고 긴 한도 확인은 멈춤(0.3s)을 한도(1s)보다 충분히 짧게 둡니다.
 *
 * Docker가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisExchangeQuoteRepositoryTimeoutTest {

    private static final String PASSWORD = "test-only";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2.11-bookworm")
            .withCommand("redis-server", "--requirepass", PASSWORD)
            .withExposedPorts(6379);

    private QuoteRedisConnection quoteConnection;
    /** Redis를 멈추고, 멈춤이 끝난 뒤 저장 결과를 확인하는 연결입니다. 멈춤보다 오래 기다립니다. */
    private QuoteRedisConnection adminConnection;

    @BeforeEach
    void setUp() {
        adminConnection = connect(Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        if (quoteConnection != null) {
            quoteConnection.destroy();
        }
        adminConnection.destroy();
    }

    @Test
    void shortTimeoutFailsWith503ButQuoteIsStoredAfterStall() throws Exception {
        quoteConnection = connect(Duration.ofMillis(200));
        RedisExchangeQuoteRepository repository = new RedisExchangeQuoteRepository(quoteConnection);
        ExchangeQuote quote = newQuote();

        Instant stallEnds = stallRedis(Duration.ofSeconds(2));
        BusinessException exception = assertThrows(BusinessException.class, () -> repository.save(quote));
        waitUntil(stallEnds);

        assertEquals(ExchangeQuoteErrorCode.QUOTE_STORE_UNAVAILABLE, exception.getErrorCode());
        // 앱은 503으로 답했지만 Redis는 멈춤이 풀린 뒤 SET을 실행했다
        assertTrue(adminConnection.template().hasKey(RedisExchangeQuoteRepository.KEY_PREFIX + quote.getId()));
    }

    @Test
    void currentTimeoutWaitsThroughShortStall() {
        quoteConnection = connect(Duration.ofSeconds(1));
        RedisExchangeQuoteRepository repository = new RedisExchangeQuoteRepository(quoteConnection);
        ExchangeQuote quote = newQuote();

        stallRedis(Duration.ofMillis(300));
        assertDoesNotThrow(() -> repository.save(quote));

        assertTrue(repository.findByIdAndUserId(quote.getId(), quote.getUserId()).isPresent());
    }

    /** Redis가 stall 동안 모든 클라이언트의 명령을 붙잡게 합니다. 돌려준 시각까지 멈춰 있습니다. */
    private Instant stallRedis(Duration stall) {
        Instant sent = Instant.now();
        adminConnection.template().execute(connection -> connection.execute("CLIENT",
                "PAUSE".getBytes(), String.valueOf(stall.toMillis()).getBytes(), "ALL".getBytes()), true);
        return sent.plus(stall);
    }

    /** 멈춤이 끝나고 붙잡혔던 명령이 실행될 때까지 기다립니다. */
    private static void waitUntil(Instant stallEnds) throws InterruptedException {
        long remaining = Duration.between(Instant.now(), stallEnds).toMillis();
        Thread.sleep(Math.max(0, remaining) + 200);
    }

    private static QuoteRedisConnection connect(Duration commandTimeout) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        server.setPassword(RedisPassword.of(PASSWORD));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(server,
                LettuceClientConfiguration.builder().commandTimeout(commandTimeout).build());
        factory.afterPropertiesSet();
        factory.start();
        QuoteRedisConnection connection = new QuoteRedisConnection(factory);
        // 첫 연결 준비가 확인 대상(멈춤 중 저장)에 섞이지 않도록 먼저 연결해 둔다.
        // 연결 준비에도 명령 대기 한도가 적용돼 200ms면 첫 시도가 실패할 수 있으므로 앱의 미리 연결처럼 다시 시도한다
        RuntimeException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                connection.template().execute(redis -> redis.ping(), true);
                return connection;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    private static ExchangeQuote newQuote() {
        return OrderFixtures.wonToYenQuote(UUID.randomUUID(), QuoteFixtures.savedRate(ForeignCurrency.JPY, "838.30"),
                Instant.now());
    }
}
