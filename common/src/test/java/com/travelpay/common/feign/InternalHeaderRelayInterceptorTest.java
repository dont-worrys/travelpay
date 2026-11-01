package com.travelpay.common.feign;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.RequestAttributeCurrentUserProvider;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalHeaderRelayInterceptorTest {

    private static final AuthenticatedUser CURRENT_USER = new AuthenticatedUser(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), AuthRole.USER);
    private static final String EXPLICIT_USER_ID = "4c6a0178-266b-4f1e-b811-a2767026be70";

    private final InternalHeaderRelayInterceptor interceptor =
            new InternalHeaderRelayInterceptor(() -> Optional.of(CURRENT_USER));

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void relaysTheVerifiedRequesterAndTheRequestIdOfTheCurrentRequest() {
        startRequest("request-1");
        var template = new RequestTemplate();

        interceptor.apply(template);

        assertEquals(List.of(CURRENT_USER.userId().toString()), values(template, AuthHeaders.USER_ID));
        assertEquals(List.of("USER"), values(template, AuthHeaders.USER_ROLE));
        assertEquals(List.of("request-1"), values(template, AuthHeaders.REQUEST_ID));
    }

    @Test
    void keepsTheRequesterTheClientSetWithoutAddingASecondValue() {
        startRequest("request-1");
        var template = new RequestTemplate()
                .header(AuthHeaders.USER_ID, EXPLICIT_USER_ID)
                .header(AuthHeaders.USER_ROLE, "MASTER");

        interceptor.apply(template);

        assertEquals(List.of(EXPLICIT_USER_ID), values(template, AuthHeaders.USER_ID));
        assertEquals(List.of("MASTER"), values(template, AuthHeaders.USER_ROLE));
        assertEquals(List.of("request-1"), values(template, AuthHeaders.REQUEST_ID));
    }

    @Test
    void treatsTheRequesterHeadersAsAPairAndIgnoresTheirLetterCase() {
        startRequest(null);
        var template = new RequestTemplate().header("user-id", EXPLICIT_USER_ID);

        interceptor.apply(template);

        assertEquals(List.of(EXPLICIT_USER_ID), values(template, AuthHeaders.USER_ID));
        assertFalse(template.headers().containsKey(AuthHeaders.USER_ROLE));
    }

    @Test
    void addsNoRequesterWhenTheClientSetOnlyTheRole() {
        startRequest(null);
        var template = new RequestTemplate().header(AuthHeaders.USER_ROLE, "MASTER");

        interceptor.apply(template);

        assertFalse(template.headers().containsKey(AuthHeaders.USER_ID));
        assertEquals(List.of("MASTER"), values(template, AuthHeaders.USER_ROLE));
    }

    @Test
    void keepsTheRequestIdTheClientSet() {
        startRequest("request-1");
        var template = new RequestTemplate().header(AuthHeaders.REQUEST_ID, "client-request");

        interceptor.apply(template);

        assertEquals(List.of("client-request"), values(template, AuthHeaders.REQUEST_ID));
    }

    @Test
    void addsNothingOutsideARequest() {
        var template = new RequestTemplate();

        new InternalHeaderRelayInterceptor(new RequestAttributeCurrentUserProvider()).apply(template);

        assertTrue(template.headers().isEmpty());
    }

    @Test
    void relaysOnlyTheRequestIdWhenTheRequestHasNoVerifiedRequester() {
        startRequest(" request-1 ");
        var template = new RequestTemplate();

        new InternalHeaderRelayInterceptor(Optional::empty).apply(template);

        assertFalse(template.headers().containsKey(AuthHeaders.USER_ID));
        assertFalse(template.headers().containsKey(AuthHeaders.USER_ROLE));
        assertEquals(List.of("request-1"), values(template, AuthHeaders.REQUEST_ID));
    }

    @Test
    void doesNotRelayABlankRequestId() {
        startRequest("  ");
        var template = new RequestTemplate();

        interceptor.apply(template);

        assertFalse(template.headers().containsKey(AuthHeaders.REQUEST_ID));
    }

    @Test
    void requiresTheCurrentUserProvider() {
        assertThrows(NullPointerException.class, () -> new InternalHeaderRelayInterceptor(null));
    }

    private static void startRequest(String requestId) {
        var request = new MockHttpServletRequest();
        if (requestId != null) {
            request.addHeader(AuthHeaders.REQUEST_ID, requestId);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static List<String> values(RequestTemplate template, String name) {
        return List.copyOf(template.headers().get(name));
    }
}
