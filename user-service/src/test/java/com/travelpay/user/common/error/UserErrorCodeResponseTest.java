package com.travelpay.user.common.error;

import java.util.stream.Stream;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserErrorCodeResponseTest {

    private MockMvc mvc;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ErrorProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @MethodSource("userErrors")
    void userBusinessErrorUsesTheCommonResponseAndItsHttpStatus(
            UserErrorCode errorCode, int expectedStatus, String expectedCode, String expectedMessage
    ) throws Exception {
        var result = mvc.perform(get("/test/user-errors").param("code", errorCode.name()))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        var expected = mapper.createObjectNode()
                .put("success", false)
                .putNull("data");
        expected.putObject("error")
                .put("code", expectedCode)
                .put("message", expectedMessage)
                .putArray("details");

        assertEquals(expected, mapper.readTree(result.getResponse().getContentAsString(UTF_8)));
    }

    private static Stream<Arguments> userErrors() {
        return Stream.of(
                Arguments.of(UserErrorCode.EMAIL_ALREADY_EXISTS, 409,
                        "USER_EMAIL_ALREADY_EXISTS", "이미 사용 중인 이메일입니다."),
                Arguments.of(UserErrorCode.PHONE_ALREADY_EXISTS, 409,
                        "USER_PHONE_ALREADY_EXISTS", "이미 사용 중인 휴대폰 번호입니다."),
                Arguments.of(UserErrorCode.TERMS_CONSENT_INVALID, 400,
                        "USER_TERMS_CONSENT_INVALID", "필수 약관 동의와 적용 버전을 확인해 주세요."),
                Arguments.of(UserErrorCode.INVALID_CREDENTIALS, 401,
                        "USER_INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."),
                Arguments.of(UserErrorCode.ACCOUNT_SUSPENDED, 403,
                        "USER_ACCOUNT_SUSPENDED", "이용이 정지된 계정입니다."),
                Arguments.of(UserErrorCode.ACCOUNT_CLOSED, 403,
                        "USER_ACCOUNT_CLOSED", "이용이 종료된 계정입니다."),
                Arguments.of(UserErrorCode.RECIPIENT_NOT_FOUND, 404,
                        "USER_RECIPIENT_NOT_FOUND", "수취인을 찾을 수 없습니다."),
                Arguments.of(UserErrorCode.NOT_FOUND, 404,
                        "USER_NOT_FOUND", "회원을 찾을 수 없습니다.")
        );
    }

    @TestComponent
    @RestController
    static class ErrorProbeController {

        @GetMapping("/test/user-errors")
        void fail(@RequestParam("code") UserErrorCode errorCode) {
            throw new BusinessException(errorCode);
        }
    }
}
