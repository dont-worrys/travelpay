package com.travelpay.user.presentation.dto;

import java.util.stream.Stream;

import com.travelpay.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsrfTokenResponseTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void returnsTheHeaderNameAndTokenInsideTheSuccessEnvelope() {
        var response = ApiResponse.success(new CsrfTokenResponse("Csrf-Token", "csrf-token-for-test"));

        assertEquals(mapper.readTree("""
                {
                  "success": true,
                  "data": {
                    "headerName": "Csrf-Token",
                    "token": "csrf-token-for-test"
                  },
                  "error": null
                }
                """), mapper.readTree(mapper.writeValueAsString(response)));
    }

    @Test
    void stringRepresentationsDoNotExposeTheToken() {
        var token = "sensitive-csrf-token-for-test";
        var response = new CsrfTokenResponse("Csrf-Token", token);

        assertFalse(response.toString().contains(token));
        assertFalse(ApiResponse.success(response).toString().contains(token));
    }

    @ParameterizedTest
    @MethodSource("incompleteResponses")
    void rejectsAnIncompleteTokenResponse(String headerName, String token) {
        assertThrows(IllegalArgumentException.class, () -> new CsrfTokenResponse(headerName, token));
    }

    private static Stream<Arguments> incompleteResponses() {
        return Stream.of(
                Arguments.of(null, "csrf-token-for-test"),
                Arguments.of("", "csrf-token-for-test"),
                Arguments.of(" \t", "csrf-token-for-test"),
                Arguments.of("Csrf-Token", null),
                Arguments.of("Csrf-Token", ""),
                Arguments.of("Csrf-Token", " \t")
        );
    }
}
