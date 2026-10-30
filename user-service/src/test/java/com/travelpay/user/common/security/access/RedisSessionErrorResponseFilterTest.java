package com.travelpay.user.common.security.access;

import com.travelpay.common.error.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.data.redis.RedisSessionRepository;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisSessionErrorResponseFilterTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RedisSessionErrorResponseFilter filter =
            new RedisSessionErrorResponseFilter(new SecurityErrorResponseWriter(mapper));

    @Test
    void returnsTheCommon503EnvelopeWithoutDeletingAnExistingCookie() throws Exception {
        var response = new MockHttpServletResponse();
        response.addHeader("Set-Cookie", "SESSION=fixture-cookie; Path=/; HttpOnly");

        filter.doFilter(new MockHttpServletRequest(), response, (request, target) -> {
            throw new RedisConnectionFailureException("connection failure for test");
        });

        assertEquals(503, response.getStatus());
        var body = mapper.readTree(response.getContentAsString(UTF_8));
        assertEquals(CommonErrorCode.SERVICE_UNAVAILABLE.getCode(), body.get("error").get("code").asString());
        assertFalse(body.get("success").asBoolean());
        assertEquals("SESSION=fixture-cookie; Path=/; HttpOnly", response.getHeader("Set-Cookie"));
    }

    @Test
    void sessionSerializationWriteFailureRemainsServiceUnavailable() throws Exception {
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (request, target) -> {
            throw new SerializationException("session cannot be encoded for test");
        });

        assertEquals(503, response.getStatus());
    }

    @Test
    void propagatesFailuresWhenTheResponseHasAlreadyBeenCommitted() throws Exception {
        var response = new MockHttpServletResponse();
        response.flushBuffer();
        var failure = new RedisConnectionFailureException("late failure for test");

        assertSame(failure, assertThrows(RedisConnectionFailureException.class,
                () -> filter.doFilter(new MockHttpServletRequest(), response, (request, target) -> {
                    throw failure;
                })));
    }

    @Test
    void doesNotHideUnrelatedProgrammingErrorsAsSessionStorageFailures() {
        var failure = new IllegalStateException("unrelated failure for test");

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                        (request, target) -> { throw failure; })));
    }

    @Test
    void aKnownRepositoryInvalidationIsAConflictWithoutDeletingTheCookie() throws Exception {
        var failure = invalidatedSession();
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (request, target) -> { throw failure; });

        assertEquals(409, response.getStatus());
        assertEquals("COMMON_CONFLICT", mapper.readTree(response.getContentAsString(UTF_8))
                .get("error").get("code").asString());
        assertNull(response.getHeader("Set-Cookie"));
    }

    @Test
    void theSameMessageFromUnrelatedApplicationCodeIsNotMisclassifiedAsAConflict() {
        var failure = new IllegalStateException("Session was invalidated");

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                        (request, target) -> { throw failure; })));
    }

    @Test
    void aKnownConflictAfterCommitStillPropagatesBecauseTheResponseCannotBeReplaced() throws Exception {
        var failure = invalidatedSession();
        var response = new MockHttpServletResponse();
        response.flushBuffer();

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> filter.doFilter(new MockHttpServletRequest(), response,
                        (request, target) -> { throw failure; })));
    }

    private IllegalStateException invalidatedSession() {
        var failure = new IllegalStateException("Session was invalidated");
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement(RedisSessionRepository.class.getName(), "save", "RedisSessionRepository.java", 81)
        });
        return failure;
    }
}
