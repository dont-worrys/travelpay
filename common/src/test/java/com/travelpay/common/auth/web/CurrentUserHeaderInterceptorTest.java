package com.travelpay.common.auth.web;

import java.util.UUID;
import java.util.stream.Stream;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.CurrentUserProvider;
import com.travelpay.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CurrentUserHeaderInterceptorTest {

    private static final String FIRST_USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String SECOND_USER_ID = "4c6a0178-266b-4f1e-b811-a2767026be70";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var provider = new RequestAttributeCurrentUserProvider();
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController(provider))
                .addInterceptors(new CurrentUserHeaderInterceptor())
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(provider))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void passesHeaderIdentityToTheControllerInsteadOfQueryValues() throws Exception {
        mvc.perform(get("/test/current-user")
                        .header(AuthHeaders.USER_ID, FIRST_USER_ID)
                        .header(AuthHeaders.USER_ROLE, "USER")
                        .param("userId", SECOND_USER_ID)
                        .param("role", "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(FIRST_USER_ID))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void makesHeaderIdentityAvailableThroughTheProvider() throws Exception {
        mvc.perform(get("/test/provider-user")
                        .header(AuthHeaders.USER_ID, SECOND_USER_ID)
                        .header(AuthHeaders.USER_ROLE, "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(SECOND_USER_ID))
                .andExpect(jsonPath("$.role").value("MASTER"));
    }

    @Test
    void letsAPublicRequestWithoutIdentityHeadersContinueWithoutCreatingASession() throws Exception {
        var result = mvc.perform(get("/test/public"))
                .andExpect(status().isOk())
                .andExpect(content().string("anonymous"))
                .andReturn();

        assertNull(result.getRequest().getSession(false));
    }

    @Test
    void rejectsMissingIdentityForCurrentUserEvenWhenQueryValuesArePresent() throws Exception {
        mvc.perform(get("/test/current-user")
                        .param("userId", SECOND_USER_ID)
                        .param("role", "MASTER"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @ParameterizedTest
    @MethodSource("invalidHeaders")
    void rejectsInvalidHeadersBeforeAControllerRunsWithASafeErrorBody(
            String[] userIds, String[] roles
    ) throws Exception {
        var request = get("/test/public").header(AuthHeaders.USER_ID, (Object[]) userIds);
        if (roles.length > 0) {
            request.header(AuthHeaders.USER_ROLE, (Object[]) roles);
        }

        var result = mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andReturn();

        assertFalse(result.getResponse().getContentAsString(UTF_8).contains("DO_NOT_ECHO"));
    }

    @Test
    void preservesAnIdentityAlreadyPreparedByServerCodeInsteadOfOverwritingItWithHeaders() throws Exception {
        var preparedUser = new AuthenticatedUser(UUID.fromString(FIRST_USER_ID), AuthRole.USER);

        mvc.perform(get("/test/current-user")
                        .requestAttr(RequestAttributeCurrentUserProvider.CURRENT_USER_ATTRIBUTE, preparedUser)
                        .header(AuthHeaders.USER_ID, SECOND_USER_ID)
                        .header(AuthHeaders.USER_ROLE, "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(FIRST_USER_ID))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void rejectsAnUnexpectedExistingAttributeInsteadOfReplacingItWithHeaderIdentity() throws Exception {
        var result = mvc.perform(get("/test/plain")
                        .requestAttr(RequestAttributeCurrentUserProvider.CURRENT_USER_ATTRIBUTE, "DO_NOT_ECHO_ATTRIBUTE")
                        .header(AuthHeaders.USER_ID, FIRST_USER_ID)
                        .header(AuthHeaders.USER_ROLE, "USER"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_SERVER_ERROR"))
                .andReturn();

        assertFalse(result.getResponse().getContentAsString(UTF_8).contains("DO_NOT_ECHO"));
    }

    @Test
    void keepsTheIdentityWithinOneRequestWhenTheInterceptorIsReused() throws Exception {
        mvc.perform(get("/test/current-user")
                        .header(AuthHeaders.USER_ID, FIRST_USER_ID)
                        .header(AuthHeaders.USER_ROLE, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(FIRST_USER_ID));

        mvc.perform(get("/test/current-user")
                        .header(AuthHeaders.USER_ID, SECOND_USER_ID)
                        .header(AuthHeaders.USER_ROLE, "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(SECOND_USER_ID));

        mvc.perform(get("/test/public"))
                .andExpect(status().isOk())
                .andExpect(content().string("anonymous"));
    }

    private static Stream<Arguments> invalidHeaders() {
        return Stream.of(
                Arguments.of(new String[]{"DO_NOT_ECHO_INVALID_UUID"}, new String[]{"USER"}),
                Arguments.of(new String[]{FIRST_USER_ID}, new String[]{}),
                Arguments.of(new String[]{FIRST_USER_ID, FIRST_USER_ID}, new String[]{"USER"})
        );
    }

    @RestController
    static class ProbeController {

        private final CurrentUserProvider provider;

        ProbeController(CurrentUserProvider provider) {
            this.provider = provider;
        }

        @GetMapping("/test/current-user")
        AuthenticatedUser currentUser(@CurrentUser AuthenticatedUser user) {
            return user;
        }

        @GetMapping("/test/provider-user")
        AuthenticatedUser providerUser() {
            return provider.requireCurrentUser();
        }

        @GetMapping("/test/public")
        String publicEndpoint() {
            return provider.findCurrentUser().map(user -> user.userId().toString()).orElse("anonymous");
        }

        @GetMapping("/test/plain")
        String plainEndpoint() {
            return "public";
        }
    }
}
