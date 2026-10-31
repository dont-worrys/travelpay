package com.travelpay.user.presentation.dto;

import java.util.UUID;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.user.application.dto.UserRealNameResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UserRealNameResponseTest {

    private static final UUID USER_ID = UUID.fromString("4c6a0178-266b-4f1e-b811-a2767026be70");

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void returnsOnlyTheMemberIdAndRealNameInsideTheSuccessEnvelope() {
        var response = ApiResponse.success(UserRealNameResponse.from(new UserRealNameResult(USER_ID, "홍길동")));

        assertEquals(mapper.readTree("""
                {
                  "success": true,
                  "data": {
                    "userId": "4c6a0178-266b-4f1e-b811-a2767026be70",
                    "realName": "홍길동"
                  },
                  "error": null
                }
                """), mapper.readTree(mapper.writeValueAsString(response)));
    }

    @Test
    void stringRepresentationsDoNotExposeTheRealName() {
        var response = UserRealNameResponse.from(new UserRealNameResult(USER_ID, "홍길동"));

        assertFalse(response.toString().contains("홍길동"));
        assertFalse(ApiResponse.success(response).toString().contains("홍길동"));
    }
}
