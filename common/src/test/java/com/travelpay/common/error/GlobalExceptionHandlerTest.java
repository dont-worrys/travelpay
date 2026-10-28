package com.travelpay.common.error;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mvc;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void serviceOwnedBusinessErrorKeepsItsStatusAndCode() throws Exception {
        var result = mvc.perform(get("/test/business"))
                .andExpect(status().isConflict()).andReturn();

        assertEquals("USER_EMAIL_ALREADY_EXISTS", errorBody(result).get("code").stringValue());
        assertEquals("이미 사용 중인 이메일입니다.", errorBody(result).get("message").stringValue());
        assertEquals(0, errorBody(result).get("details").size());
    }

    @Test
    void invalidFieldReturnsOnlyItsNameAndGuidanceWithoutTheRejectedValue() throws Exception {
        var result = mvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"DO_NOT_ECHO_THIS_INPUT\"}"))
                .andExpect(status().isBadRequest()).andReturn();

        assertEquals("COMMON_INVALID_REQUEST", errorBody(result).get("code").stringValue());
        var detail = errorBody(result).get("details").get(0);
        assertEquals("value", detail.get("field").stringValue());
        assertEquals("숫자만 입력해 주세요.", detail.get("message").stringValue());
        assertEquals(2, detail.size());
        assertFalse(result.getResponse().getContentAsString(UTF_8).contains("DO_NOT_ECHO_THIS_INPUT"));
    }

    @Test
    void malformedJsonReturnsTheSharedBadRequestWithoutParserDetails() throws Exception {
        var result = mvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON).content("{DO_NOT_ECHO_PARSER_INPUT"))
                .andExpect(status().isBadRequest()).andReturn();

        assertEquals("COMMON_INVALID_REQUEST", errorBody(result).get("code").stringValue());
        assertFalse(result.getResponse().getContentAsString(UTF_8).contains("DO_NOT_ECHO"));
    }

    @Test
    void wrongMethodKeepsTheAllowHeader() throws Exception {
        var result = mvc.perform(get("/test/validation"))
                .andExpect(status().isMethodNotAllowed()).andReturn();

        assertTrue(result.getResponse().getHeader("Allow").contains("POST"));
        assertEquals("COMMON_METHOD_NOT_ALLOWED", errorBody(result).get("code").stringValue());
    }

    @Test
    void unsupportedMediaTypeKeeps415() throws Exception {
        var result = mvc.perform(post("/test/validation")
                        .contentType(MediaType.TEXT_PLAIN).content("DO_NOT_ECHO_THIS_INPUT"))
                .andExpect(status().isUnsupportedMediaType()).andReturn();

        assertEquals("COMMON_UNSUPPORTED_MEDIA_TYPE", errorBody(result).get("code").stringValue());
    }

    @Test
    void missingRouteUsesTheSharedNotFoundResponse() throws Exception {
        var result = mvc.perform(get("/test/missing"))
                .andExpect(status().isNotFound()).andReturn();

        assertEquals("COMMON_NOT_FOUND", errorBody(result).get("code").stringValue());
    }

    @Test
    void typeMismatchDoesNotExposeTheInput() throws Exception {
        var result = mvc.perform(get("/test/number").param("number", "DO_NOT_ECHO_THIS_INPUT"))
                .andExpect(status().isBadRequest()).andReturn();

        assertEquals("COMMON_INVALID_REQUEST", errorBody(result).get("code").stringValue());
        assertFalse(result.getResponse().getContentAsString(UTF_8).contains("DO_NOT_ECHO"));
    }

    @Test
    void frameworkErrorKeeps503WithoutExposingItsReason() throws Exception {
        var result = mvc.perform(get("/test/unavailable"))
                .andExpect(status().isServiceUnavailable()).andReturn();

        assertEquals("COMMON_SERVICE_UNAVAILABLE", errorBody(result).get("code").stringValue());
        assertFalse(result.getResponse().getContentAsString(UTF_8).contains("DO_NOT_EXPOSE"));
    }

    @Test
    void unexpectedFailureReturnsASafe500() throws Exception {
        var result = mvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError()).andReturn();

        assertEquals("COMMON_INTERNAL_SERVER_ERROR", errorBody(result).get("code").stringValue());
        assertEquals(0, errorBody(result).get("details").size());
        assertFalse(result.getResponse().getContentAsString(UTF_8).contains("DO_NOT_EXPOSE"));
    }

    @Test
    void modelBindingFailureDoesNotExposeTheRejectedValueOrConversionDetails() throws Exception {
        var result = mvc.perform(get("/test/form").param("count", "DO_NOT_ECHO_BINDING_VALUE"))
                .andExpect(status().isBadRequest()).andReturn();

        assertEquals("COMMON_INVALID_REQUEST", errorBody(result).get("code").stringValue());
        assertEquals("count", errorBody(result).get("details").get(0).get("field").stringValue());
        var response = result.getResponse().getContentAsString(UTF_8);
        assertFalse(response.contains("DO_NOT_ECHO"));
        assertFalse(response.contains("Failed to convert"));
    }

    @Test
    void annotatedStatusKeeps404WithoutTheAnnotationReason() throws Exception {
        var result = mvc.perform(get("/test/annotated"))
                .andExpect(status().isNotFound()).andReturn();

        assertEquals("COMMON_NOT_FOUND", errorBody(result).get("code").stringValue());
        assertFalse(result.getResponse().getContentAsString(UTF_8).contains("DO_NOT_EXPOSE"));
    }

    @Test
    void wrappedFrameworkFailureKeeps503WithoutExposingEitherReason() throws Exception {
        var result = mvc.perform(get("/test/wrapped-unavailable"))
                .andExpect(status().isServiceUnavailable()).andReturn();

        assertEquals("COMMON_SERVICE_UNAVAILABLE", errorBody(result).get("code").stringValue());
        assertFalse(result.getResponse().getContentAsString(UTF_8).contains("DO_NOT_EXPOSE"));
    }

    @Test
    void wrappedMethodErrorPreservesTheAllowHeader() throws Exception {
        var result = mvc.perform(get("/test/wrapped-method"))
                .andExpect(status().isMethodNotAllowed()).andReturn();

        assertEquals("POST", result.getResponse().getHeader("Allow"));
        assertEquals("COMMON_METHOD_NOT_ALLOWED", errorBody(result).get("code").stringValue());
    }

    @Test
    void wrappedBusinessErrorKeepsTheServiceOwnedCode() throws Exception {
        var result = mvc.perform(get("/test/wrapped-business"))
                .andExpect(status().isConflict()).andReturn();

        assertEquals("USER_EMAIL_ALREADY_EXISTS", errorBody(result).get("code").stringValue());
        assertFalse(result.getResponse().getContentAsString(UTF_8).contains("DO_NOT_EXPOSE"));
    }

    @Test
    void wrappedAnnotatedStatusKeeps404WithoutTheAnnotationReason() throws Exception {
        var result = mvc.perform(get("/test/wrapped-annotated"))
                .andExpect(status().isNotFound()).andReturn();

        assertEquals("COMMON_NOT_FOUND", errorBody(result).get("code").stringValue());
        assertFalse(result.getResponse().getContentAsString(UTF_8).contains("DO_NOT_EXPOSE"));
    }

    @Test
    void explicitOuterStatusTakesPrecedenceOverItsCause() throws Exception {
        var result = mvc.perform(get("/test/annotated-with-cause"))
                .andExpect(status().isNotFound()).andReturn();

        assertEquals("COMMON_NOT_FOUND", errorBody(result).get("code").stringValue());
    }

    @Test
    void unmappedFrameworkStatusKeepsItsStatusAndUsesTheFallbackCode() throws Exception {
        var result = mvc.perform(get("/test/unmapped-status"))
                .andExpect(status().is(498)).andReturn();

        assertEquals("COMMON_HTTP_ERROR", errorBody(result).get("code").stringValue());
        assertFalse(result.getResponse().getContentAsString(UTF_8).contains("DO_NOT_EXPOSE"));
    }

    @Test
    void cyclicCausesTerminateWithASafe500() {
        var first = new IllegalStateException("DO_NOT_EXPOSE_CYCLE");
        var second = new RuntimeException(first);
        first.initCause(second);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var response = new GlobalExceptionHandler().handleUnexpectedException(first);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        });
    }

    private JsonNode errorBody(MvcResult result) throws Exception {
        var body = mapper.readTree(result.getResponse().getContentAsString(UTF_8));
        assertEquals(3, body.size());
        assertNotNull(body.get("success"));
        assertFalse(body.get("success").booleanValue());
        assertNotNull(body.get("data"));
        assertTrue(body.get("data").isNull());
        assertNotNull(body.get("error"));
        assertTrue(body.get("error").isObject());
        return body.get("error");
    }

    @RestController
    static class ProbeController {

        @GetMapping("/test/wrapped-unavailable")
        String wrappedUnavailable() {
            throw new IllegalStateException("DO_NOT_EXPOSE_WRAPPER",
                    new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DO_NOT_EXPOSE_REASON"));
        }

        @GetMapping("/test/wrapped-method")
        String wrappedMethod() {
            throw new IllegalStateException("DO_NOT_EXPOSE_WRAPPER",
                    new HttpRequestMethodNotSupportedException("PATCH", List.of("POST")));
        }

        @GetMapping("/test/wrapped-business")
        String wrappedBusiness() {
            throw new IllegalStateException("DO_NOT_EXPOSE_WRAPPER",
                    new BusinessException(SampleError.EMAIL_ALREADY_EXISTS));
        }

        @GetMapping("/test/wrapped-annotated")
        String wrappedAnnotated() {
            throw new IllegalStateException("DO_NOT_EXPOSE_WRAPPER", new AnnotatedNotFoundException());
        }

        @GetMapping("/test/annotated-with-cause")
        String annotatedWithCause() {
            var failure = new AnnotatedNotFoundException();
            failure.initCause(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
            throw failure;
        }

        @GetMapping("/test/form")
        String form(@Valid @ModelAttribute FormQuery query) {
            return "ok";
        }

        @GetMapping("/test/annotated")
        String annotated() {
            throw new AnnotatedNotFoundException();
        }

        @GetMapping("/test/business")
        String business() {
            throw new BusinessException(SampleError.EMAIL_ALREADY_EXISTS);
        }

        @PostMapping(value = "/test/validation", consumes = MediaType.APPLICATION_JSON_VALUE)
        String validate(@Valid @RequestBody CodeRequest request) {
            return "ok";
        }

        @GetMapping("/test/number")
        String number(@RequestParam Integer number) {
            return number.toString();
        }

        @GetMapping("/test/unavailable")
        String unavailable() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DO_NOT_EXPOSE_INTERNAL_REASON");
        }

        @GetMapping("/test/unmapped-status")
        String unmappedStatus() {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(498), "DO_NOT_EXPOSE_INTERNAL_REASON");
        }

        @GetMapping("/test/unexpected")
        String unexpected() {
            throw new IllegalStateException("DO_NOT_EXPOSE_INTERNAL_DETAIL");
        }
    }

    record CodeRequest(@Pattern(regexp = "[0-9]+", message = "숫자만 입력해 주세요.") String value) {
    }

    record FormQuery(Integer count) {
    }

    @ResponseStatus(code = HttpStatus.NOT_FOUND, reason = "DO_NOT_EXPOSE_ANNOTATED_REASON")
    static class AnnotatedNotFoundException extends RuntimeException {
    }

    enum SampleError implements ErrorCode {
        EMAIL_ALREADY_EXISTS;

        public String getCode() { return "USER_EMAIL_ALREADY_EXISTS"; }
        public String getMessage() { return "이미 사용 중인 이메일입니다."; }
        public int getStatus() { return 409; }
    }
}
