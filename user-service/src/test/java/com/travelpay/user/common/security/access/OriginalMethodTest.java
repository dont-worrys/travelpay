package com.travelpay.user.common.security.access;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OriginalMethodTest {

    @ParameterizedTest
    @CsvSource({"GET,false", "HEAD,false", "OPTIONS,false", "POST,true", "PUT,true", "PATCH,true", "DELETE,true"})
    void readsTheOneAllowedMethodAndWhetherItChangesState(String value, boolean changesState) {
        var method = OriginalMethod.from(request(value)).orElseThrow();

        assertEquals(HttpMethod.valueOf(value), method.method());
        assertEquals(changesState, method.changesState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "get", "Post", "TRACE", "CONNECT", "GET,POST"})
    void rejectsAValueTheSpecDoesNotAllow(String value) {
        assertTrue(OriginalMethod.from(request(value)).isEmpty());
    }

    @Test
    void rejectsAMissingHeader() {
        assertTrue(OriginalMethod.from(request()).isEmpty());
    }

    @Test
    void rejectsTheHeaderSentMoreThanOnce() {
        assertTrue(OriginalMethod.from(request("GET", "GET")).isEmpty());
        assertTrue(OriginalMethod.from(request("GET", "POST")).isEmpty());
    }

    private static MockHttpServletRequest request(String... values) {
        var request = new MockHttpServletRequest("POST", "/internal/v1/auth/session");
        for (var value : values) {
            request.addHeader("Original-Method", value);
        }
        return request;
    }
}
