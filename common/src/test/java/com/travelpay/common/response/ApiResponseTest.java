package com.travelpay.common.response;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiResponseTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void successPlacesTheResponseDtoInsideData() {
        var response = ApiResponse.success(new MemberResponse(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000")));

        assertEquals(mapper.readTree("""
                {
                  "success": true,
                  "data": {"userId": "550e8400-e29b-41d4-a716-446655440000"},
                  "error": null
                }
                """), mapper.readTree(mapper.writeValueAsString(response)));
    }

    @Test
    void successWithoutDataKeepsTheEnvelopeFields() {
        var response = ApiResponse.<Void>success(null);

        assertEquals(mapper.readTree("""
                {"success": true, "data": null, "error": null}
                """), mapper.readTree(mapper.writeValueAsString(response)));
    }

    @Test
    void failurePlacesTheExistingErrorContractInsideError() {
        var response = ApiResponse.failure(ErrorResponse.of(CommonErrorCode.UNAUTHORIZED));

        assertEquals(mapper.readTree("""
                {
                  "success": false,
                  "data": null,
                  "error": {
                    "code": "COMMON_UNAUTHORIZED",
                    "message": "로그인이 필요합니다.",
                    "details": []
                  }
                }
                """), mapper.readTree(mapper.writeValueAsString(response)));
    }

    @Test
    void validationFailurePreservesFieldNamesAndGuidance() {
        var error = ErrorResponse.of(CommonErrorCode.INVALID_REQUEST, List.of(
                new ErrorResponse.FieldError("email", "이메일 형식을 확인해 주세요.")));

        var response = ApiResponse.failure(error);

        assertEquals(mapper.readTree("""
                {
                  "success": false,
                  "data": null,
                  "error": {
                    "code": "COMMON_INVALID_REQUEST",
                    "message": "잘못된 요청입니다.",
                    "details": [{"field": "email", "message": "이메일 형식을 확인해 주세요."}]
                  }
                }
                """), mapper.readTree(mapper.writeValueAsString(response)));
    }

    @Test
    void pageSuccessKeepsItemsAndPaginationInsideData() {
        var page = new PageResponse<>(List.of("first"), 0, 20, 1, 1);

        var response = ApiResponse.success(page);

        assertEquals(mapper.readTree("""
                {
                  "success": true,
                  "data": {
                    "items": ["first"],
                    "page": 0,
                    "size": 20,
                    "totalElements": 1,
                    "totalPages": 1
                  },
                  "error": null
                }
                """), mapper.readTree(mapper.writeValueAsString(response)));
    }

    @Test
    void failureRequiresErrorInformation() {
        assertThrows(IllegalArgumentException.class, () -> ApiResponse.failure(null));
    }

    @ParameterizedTest
    @MethodSource("contradictoryStates")
    void directConstructionRejectsContradictoryStates(
            boolean success, String data, ErrorResponse error) {
        assertThrows(IllegalArgumentException.class, () -> new ApiResponse<>(success, data, error));
    }

    private static Stream<Arguments> contradictoryStates() {
        var error = ErrorResponse.of(CommonErrorCode.UNAUTHORIZED);
        return Stream.of(
                Arguments.of(true, "result", error),
                Arguments.of(true, null, error),
                Arguments.of(false, "result", error),
                Arguments.of(false, "result", null),
                Arguments.of(false, null, null)
        );
    }

    private record MemberResponse(UUID userId) {
    }
}
