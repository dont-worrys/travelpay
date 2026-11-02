package com.travelpay.gateway.request;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InternalHeaderResetFilterTest {

    private final InternalHeaderResetFilter filter =
            new InternalHeaderResetFilter(new ClientIpResolver(new ClientIpProperties(List.of(), List.of())));
    private final InternalHeaderResetFilter behindOurProxy = new InternalHeaderResetFilter(
            new ClientIpResolver(new ClientIpProperties(List.of(), List.of("198.51.100.0/24"))));

    @Test
    void withoutAConnectionAddressTheIpHeadersTheBrowserSentAreStillDropped() {
        var forwarded = forward(filter, MockServerHttpRequest.get("/api/v1/terms")
                .header("Client-Ip", "203.0.113.9").header("CF-Connecting-IP", "203.0.113.8").build());

        assertNull(forwarded.getHeaders().getFirst("Client-Ip"));
        assertNull(forwarded.getHeaders().getFirst("CF-Connecting-IP"));
    }

    @Test
    void theConnectionAddressBecomesTheClientIp() {
        var forwarded = forward(filter, MockServerHttpRequest.get("/api/v1/terms")
                .remoteAddress(new InetSocketAddress("198.51.100.4", 50000))
                .header("Client-Ip", "203.0.113.9").build());

        assertEquals("198.51.100.4", forwarded.getHeaders().getFirst("Client-Ip"));
    }

    @Test
    void oneCloudflareIpFromOurProxyBecomesTheClientIp() {
        var forwarded = forward(behindOurProxy, fromOurProxy().header("CF-Connecting-IP", "203.0.113.8").build());

        assertEquals("203.0.113.8", forwarded.getHeaders().getFirst("Client-Ip"));
    }

    @Test
    void twoCloudflareIpValuesAreNotTrustedBecauseTheRealOneIsUnknown() {
        var forwarded = forward(behindOurProxy,
                fromOurProxy().header("CF-Connecting-IP", "203.0.113.8", "203.0.113.9").build());

        assertEquals("198.51.100.4", forwarded.getHeaders().getFirst("Client-Ip"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"User-Id", "User-Role", "Original-Method", "Request-Id", "Client-Ip", "CF-Connecting-IP",
            "traceparent", "tracestate", "baggage", "b3", "X-B3-TraceId", "X-B3-SpanId", "X-B3-ParentSpanId",
            "X-B3-Sampled", "X-B3-Flags", "X-Amzn-Trace-Id"})
    void internalHeaderNamesCannotBeMarkedForRemovalByTheNextProxy(String headerName) {
        var forwarded = forward(filter, MockServerHttpRequest.get("/api/v1/terms")
                .header(HttpHeaders.CONNECTION, headerName).build());

        assertNull(forwarded.getHeaders().getFirst(HttpHeaders.CONNECTION));
    }

    @Test
    void internalNamesAreRemovedFromEveryConnectionLineRegardlessOfCase() {
        var forwarded = forward(filter, MockServerHttpRequest.get("/api/v1/terms")
                .header(HttpHeaders.CONNECTION, "close, uSeR-iD, USER-ROLE, original-method",
                        "rEQUEST-iD, CLIENT-IP, cf-connecting-ip, keep-alive")
                .build());

        assertEquals(List.of("close", "keep-alive"), forwarded.getHeaders().getConnection());
    }

    @Test
    void unrelatedConnectionOptionsAndTheirHeadersArePreserved() {
        var forwarded = forward(filter, MockServerHttpRequest.get("/api/v1/terms")
                .header(HttpHeaders.CONNECTION, "close, Upgrade, X-Hop-Only, Keep-Alive")
                .header(HttpHeaders.UPGRADE, "websocket")
                .header("X-Hop-Only", "remove-at-next-proxy")
                .build());

        assertEquals(List.of("close", "Upgrade", "X-Hop-Only", "Keep-Alive"),
                forwarded.getHeaders().getConnection());
        assertEquals("websocket", forwarded.getHeaders().getFirst(HttpHeaders.UPGRADE));
        assertEquals("remove-at-next-proxy", forwarded.getHeaders().getFirst("X-Hop-Only"));
    }

    @Test
    void noConnectionHeaderIsAddedWhenTheRequestHasNone() {
        var forwarded = forward(filter, MockServerHttpRequest.get("/api/v1/terms").build());

        assertNull(forwarded.getHeaders().getFirst(HttpHeaders.CONNECTION));
    }

    private static MockServerHttpRequest.BaseBuilder<?> fromOurProxy() {
        return MockServerHttpRequest.get("/api/v1/terms").remoteAddress(new InetSocketAddress("198.51.100.4", 50000));
    }

    private static ServerHttpRequest forward(InternalHeaderResetFilter filter, MockServerHttpRequest request) {
        var forwarded = new AtomicReference<ServerHttpRequest>();
        filter.filter(MockServerWebExchange.from(request), exchange -> {
            forwarded.set(exchange.getRequest());
            return Mono.empty();
        }).block();
        return forwarded.get();
    }
}
