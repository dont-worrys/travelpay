package com.travelpay.common.auth.web;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.CurrentUserProvider;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CurrentUserArgumentResolverTest {

    private static final AuthenticatedUser VERIFIED_USER = new AuthenticatedUser(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), AuthRole.USER);
    private static final String OTHER_USER_ID = "4c6a0178-266b-4f1e-b811-a2767026be70";

    @Test
    void selectsTheParameterMarkedWithCurrentUser() {
        var resolver = new CurrentUserArgumentResolver(Optional::empty);

        assertTrue(resolver.supportsParameter(parameter("currentUser")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"plainIdentity", "plainText"})
    void leavesUnannotatedParametersToOtherResolvers(String methodName) {
        var resolver = new CurrentUserArgumentResolver(Optional::empty);

        assertFalse(resolver.supportsParameter(parameter(methodName)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalidText", "invalidUuid", "invalidOptional"})
    void rejectsUnsupportedAnnotatedTypesInsteadOfAllowingOrdinaryInputBinding(String methodName) {
        var resolver = new CurrentUserArgumentResolver(Optional::empty);
        var parameter = parameter(methodName);

        assertTrue(resolver.supportsParameter(parameter));
        assertThrows(IllegalStateException.class, () -> resolver.resolveArgument(
                parameter, null, new ServletWebRequest(new MockHttpServletRequest()), null));
    }

    @Test
    void injectsTheProviderIdentityInsteadOfQueryOrHeaderValues() throws Exception {
        var mvc = mvc(() -> Optional.of(VERIFIED_USER));

        mvc.perform(get("/test/current-user")
                        .param("userId", OTHER_USER_ID)
                        .param("role", "MASTER")
                        .header(AuthHeaders.USER_ID, OTHER_USER_ID)
                        .header(AuthHeaders.USER_ROLE, "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(VERIFIED_USER.userId().toString()))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void rejectsMissingProviderIdentityEvenWhenIdentityHeadersArePresent() throws Exception {
        var mvc = mvc(Optional::empty);

        mvc.perform(get("/test/current-user")
                        .param("userId", OTHER_USER_ID)
                        .param("role", "MASTER")
                        .header(AuthHeaders.USER_ID, OTHER_USER_ID)
                        .header(AuthHeaders.USER_ROLE, "MASTER"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    void preservesProviderFailureAs503InsteadOfChangingItTo401() throws Exception {
        var mvc = mvc(() -> {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        });

        mvc.perform(get("/test/current-user"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("COMMON_SERVICE_UNAVAILABLE"));
    }

    @Test
    void wrongAnnotatedTypeProducesASafe500InsteadOfUsingQueryInput() throws Exception {
        var mvc = mvc(() -> Optional.of(VERIFIED_USER));

        var result = mvc.perform(get("/test/invalid-current-user").param("user", "DO_NOT_ECHO_QUERY"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("COMMON_INTERNAL_SERVER_ERROR"))
                .andReturn();

        assertFalse(result.getResponse().getContentAsString(UTF_8).contains("DO_NOT_ECHO"));
    }

    @Test
    void ordinaryQueryParametersKeepTheirNormalBinding() throws Exception {
        var mvc = mvc(Optional::empty);

        mvc.perform(get("/test/query").param("value", "ordinary-query-value"))
                .andExpect(status().isOk())
                .andExpect(content().string("ordinary-query-value"));
    }

    private static MockMvc mvc(CurrentUserProvider provider) {
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(provider))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static MethodParameter parameter(String methodName) {
        var method = Arrays.stream(ProbeController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        return new MethodParameter(method, 0);
    }

    @RestController
    static class ProbeController {

        @GetMapping("/test/current-user")
        AuthenticatedUser currentUser(@CurrentUser AuthenticatedUser user) {
            return user;
        }

        @GetMapping("/test/invalid-current-user")
        String invalidText(@CurrentUser String user) {
            return user;
        }

        @GetMapping("/test/query")
        String plainText(String value) {
            return value;
        }

        void plainIdentity(AuthenticatedUser user) {
        }

        void invalidUuid(@CurrentUser UUID userId) {
        }

        void invalidOptional(@CurrentUser Optional<AuthenticatedUser> user) {
        }
    }
}
