package com.travelpay.gateway.error;

import java.time.Duration;
import java.util.Set;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayErrorHandlerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final GatewayErrorHandler handler = new GatewayErrorHandler(mapper);

    @Test
    void businessErrorUsesTheSharedJsonContract() {
        var exchange = exchange();

        handler.handle(exchange, new BusinessException(CommonErrorCode.UNAUTHORIZED)).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType());
        assertEquals("COMMON_UNAUTHORIZED", errorBody(exchange).get("code").stringValue());
        assertEquals(0, errorBody(exchange).get("details").size());
    }

    @Test
    void frameworkFailureKeeps503WithoutItsInternalReason() {
        var exchange = exchange();

        handler.handle(exchange, new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "DO_NOT_EXPOSE_INTERNAL_REASON")).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_SERVICE_UNAVAILABLE", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
    }

    @Test
    void methodErrorPreservesTheAllowHeader() {
        var exchange = exchange();

        handler.handle(exchange, new MethodNotAllowedException("GET", Set.of(HttpMethod.POST)))
                .block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, exchange.getResponse().getStatusCode());
        assertEquals(Set.of(HttpMethod.POST), exchange.getResponse().getHeaders().getAllow());
        assertEquals("COMMON_METHOD_NOT_ALLOWED", errorBody(exchange).get("code").stringValue());
    }

    @Test
    void unexpectedFailureReturnsASafe500() {
        var exchange = exchange();

        handler.handle(exchange, new IllegalStateException("DO_NOT_EXPOSE_INTERNAL_DETAIL"))
                .block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_INTERNAL_SERVER_ERROR", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
    }

    @Test
    void unknownHttpStatusIsPreservedWithASafeFallbackBody() {
        var exchange = exchange();

        handler.handle(exchange, new ResponseStatusException(HttpStatusCode.valueOf(418), "DO_NOT_EXPOSE_REASON"))
                .block(Duration.ofSeconds(2));

        assertEquals(418, exchange.getResponse().getStatusCode().value());
        assertEquals("COMMON_HTTP_ERROR", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
    }

    @Test
    void committedResponseIsNotRewritten() {
        var exchange = exchange();
        exchange.getResponse().setComplete().block(Duration.ofSeconds(2));
        var failure = new IllegalStateException("already committed");

        var propagated = assertThrows(IllegalStateException.class,
                () -> handler.handle(exchange, failure).block(Duration.ofSeconds(2)));

        assertSame(failure, propagated);
    }

    @Test
    void annotatedStatusKeeps404WithoutTheAnnotationReason() {
        var exchange = exchange();

        handler.handle(exchange, new AnnotatedNotFoundException()).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_NOT_FOUND", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
    }

    @Test
    void wrappedFrameworkFailureKeeps503WithoutExposingEitherReason() {
        var exchange = exchange();
        var failure = new IllegalStateException("DO_NOT_EXPOSE_WRAPPER",
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DO_NOT_EXPOSE_REASON"));

        handler.handle(exchange, failure).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_SERVICE_UNAVAILABLE", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
    }

    @Test
    void wrappedMethodErrorPreservesTheAllowHeader() {
        var exchange = exchange();
        var failure = new IllegalStateException("DO_NOT_EXPOSE_WRAPPER",
                new MethodNotAllowedException("PATCH", Set.of(HttpMethod.POST)));

        handler.handle(exchange, failure).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, exchange.getResponse().getStatusCode());
        assertEquals(Set.of(HttpMethod.POST), exchange.getResponse().getHeaders().getAllow());
        assertEquals("COMMON_METHOD_NOT_ALLOWED", errorBody(exchange).get("code").stringValue());
    }

    @Test
    void wrappedBusinessErrorKeepsItsCodeAndStatus() {
        var exchange = exchange();
        var failure = new IllegalStateException("DO_NOT_EXPOSE_WRAPPER",
                new BusinessException(CommonErrorCode.UNAUTHORIZED));

        handler.handle(exchange, failure).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_UNAUTHORIZED", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
    }

    @Test
    void wrappedAnnotatedStatusKeeps404WithoutTheAnnotationReason() {
        var exchange = exchange();
        var failure = new IllegalStateException("DO_NOT_EXPOSE_WRAPPER", new AnnotatedNotFoundException());

        handler.handle(exchange, failure).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_NOT_FOUND", errorBody(exchange).get("code").stringValue());
        assertFalse(errorBody(exchange).toString().contains("DO_NOT_EXPOSE"));
    }

    @Test
    void explicitOuterStatusTakesPrecedenceOverItsCause() {
        var exchange = exchange();
        var failure = new AnnotatedNotFoundException();
        failure.initCause(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));

        handler.handle(exchange, failure).block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
        assertEquals("COMMON_NOT_FOUND", errorBody(exchange).get("code").stringValue());
    }

    @Test
    void headFailureKeepsItsStatusWithoutWritingAJsonBody() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.head("/test").build());

        handler.handle(exchange, new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
                .block(Duration.ofSeconds(2));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals("", exchange.getResponse().getBodyAsString().defaultIfEmpty("").block(Duration.ofSeconds(2)));
    }

    @Test
    void cyclicCausesTerminateWithASafe500() {
        var exchange = exchange();
        var first = new IllegalStateException("DO_NOT_EXPOSE_CYCLE");
        var second = new RuntimeException(first);
        first.initCause(second);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            handler.handle(exchange, first).block(Duration.ofSeconds(2));
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        });
    }

    @ResponseStatus(code = HttpStatus.NOT_FOUND, reason = "DO_NOT_EXPOSE_ANNOTATED_REASON")
    static class AnnotatedNotFoundException extends RuntimeException {
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
    }

    private JsonNode errorBody(MockServerWebExchange exchange) {
        var body = mapper.readTree(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(2)));
        assertEquals(3, body.size());
        assertNotNull(body.get("success"));
        assertFalse(body.get("success").booleanValue());
        assertNotNull(body.get("data"));
        assertTrue(body.get("data").isNull());
        assertNotNull(body.get("error"));
        assertTrue(body.get("error").isObject());
        assertNotNull(body.get("error").get("details"));
        assertTrue(body.get("error").get("details").isArray());
        return body.get("error");
    }
}
