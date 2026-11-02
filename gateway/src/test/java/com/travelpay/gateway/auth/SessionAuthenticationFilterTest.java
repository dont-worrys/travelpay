package com.travelpay.gateway.auth;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.gateway.support.StubServer;
import com.travelpay.gateway.support.StubServer.Reply;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionAuthenticationFilterTest {

    private static final String PATH = "/internal/v1/auth/session";
    private static final StubServer USER = StubServer.start();

    @AfterAll
    static void stop() {
        USER.close();
    }

    @ParameterizedTest
    @CsvSource({"401,COMMON_UNAUTHORIZED", "403,COMMON_CSRF_INVALID", "409,COMMON_CONFLICT"})
    void anAuthenticationRejectionReplacesAnExistingCachePolicyAndPreservesTheResponse(int status, String code) {
        var body = """
                {"success":false,"data":null,"error":{"code":"%s","message":"m","details":[]}}
                """.formatted(code);
        USER.reply(PATH, Reply.json(status, body));
        var sessionClient = new SessionVerificationClient(WebClient.builder().build(), URI.create(USER.uri() + PATH));
        var filter = new SessionAuthenticationFilter(sessionClient, new SessionVerificationProperties(
                URI.create(USER.uri() + PATH), Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(5)));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/wallets/me")
                .header(HttpHeaders.COOKIE, "SESSION=abc").build());
        exchange.getResponse().getHeaders().setCacheControl("public, max-age=600");

        filter.filter(exchange, ignored -> Mono.error(new AssertionError("거절한 요청은 업무 서비스로 전달하면 안 됩니다.")))
                .block(Duration.ofSeconds(5));

        var response = exchange.getResponse();
        assertEquals(HttpStatusCode.valueOf(status), response.getStatusCode());
        assertEquals(List.of("no-store"), response.getHeaders().get(HttpHeaders.CACHE_CONTROL));
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertEquals(body, response.getBodyAsString().block(Duration.ofSeconds(5)));
        assertFalse(response.getHeaders().containsHeader(HttpHeaders.SET_COOKIE));
    }

    @Test
    void aClientFilterWaitingBeforeTheConnectionIsCancelledAsAnAuthenticationOutage() {
        var cancelled = new AtomicBoolean();
        var webClient = WebClient.builder()
                .filter((request, next) -> Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true)))
                .build();
        var uri = URI.create(USER.uri() + PATH);
        var sessionClient = new SessionVerificationClient(webClient, uri);
        var filter = new SessionAuthenticationFilter(sessionClient, new SessionVerificationProperties(
                uri, Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(150)));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/wallets/me").build());

        var exception = assertThrows(BusinessException.class, () -> filter.filter(exchange,
                ignored -> Mono.error(new AssertionError("검증이 끝나지 않은 요청은 전달하면 안 됩니다.")))
                .block(Duration.ofSeconds(2)));

        assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
        assertInstanceOf(TimeoutException.class, exception.getCause());
        assertTrue(cancelled.get());
    }
}
