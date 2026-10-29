package com.travelpay.payment.transfer.controller;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.CurrentUserProvider;
import com.travelpay.common.auth.web.CurrentUserArgumentResolver;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.GlobalExceptionHandler;
import com.travelpay.common.response.PageResponse;
import com.travelpay.payment.transfer.application.TransferService;
import com.travelpay.payment.transfer.domain.TransferDirection;
import com.travelpay.payment.transfer.domain.TransferErrorCode;
import com.travelpay.payment.transfer.domain.TransferStatus;
import com.travelpay.payment.transfer.dto.RecipientResponse;
import com.travelpay.payment.transfer.dto.TransferCreateRequest;
import com.travelpay.payment.transfer.dto.TransferResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransferControllerTest {

    private static final AuthenticatedUser SENDER = new AuthenticatedUser(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), AuthRole.USER);
    private static final UUID RECEIVER_ID = UUID.fromString("4c6a0178-266b-4f1e-b811-a2767026be70");
    private static final String VALID_BODY = """
            {"receiverId":"4c6a0178-266b-4f1e-b811-a2767026be70","amount":10000,"currency":"KRW","memo":"저녁값"}
            """;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = mock(TransferService.class);
    }

    @Test
    void acceptsTransferWithCreatedStatusAndCommonResponse() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferService.request(eq(SENDER.userId()), eq("key-1"), any(TransferCreateRequest.class)))
                .thenReturn(new TransferResponse(transferId, TransferDirection.SENT, SENDER.userId(), RECEIVER_ID,
                        10_000L, "KRW", TransferStatus.REQUESTED, "저녁값",
                        Instant.parse("2026-10-28T01:02:03.123456Z")));

        mvc(SENDER).perform(post("/api/v1/transfers")
                        .header(TransferController.IDEMPOTENCY_KEY_HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-28T01:02:03.123456Z"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void passesMissingIdempotencyKeyToServiceValidation() throws Exception {
        when(transferService.request(eq(SENDER.userId()), isNull(), any(TransferCreateRequest.class)))
                .thenThrow(new BusinessException(TransferErrorCode.INVALID_IDEMPOTENCY_KEY));

        mvc(SENDER).perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("TRANSFER_INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void rejectsInvalidBodyWithFieldDetails() throws Exception {
        mvc(SENDER).perform(post("/api/v1/transfers")
                        .header(TransferController.IDEMPOTENCY_KEY_HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"receiverId":"4c6a0178-266b-4f1e-b811-a2767026be70","amount":0,"currency":"krw"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.length()").value(2));
        verifyNoInteractions(transferService);
    }

    @Test
    void requiresAuthenticatedUser() throws Exception {
        mvc(null).perform(get("/api/v1/transfers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
        verifyNoInteractions(transferService);
    }

    @Test
    void returnsNotFoundForTransferOutsideUserScope() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferService.get(SENDER.userId(), transferId))
                .thenThrow(new BusinessException(TransferErrorCode.NOT_FOUND));

        mvc(SENDER).perform(get("/api/v1/transfers/{transferId}", transferId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void cancelsTransferWithOkAndCancelledStatus() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferService.cancel(SENDER.userId(), transferId))
                .thenReturn(new TransferResponse(transferId, TransferDirection.SENT, SENDER.userId(), RECEIVER_ID,
                        10_000L, "KRW", TransferStatus.CANCELLED, null, Instant.parse("2026-10-29T01:02:03Z")));

        mvc(SENDER).perform(post("/api/v1/transfers/{transferId}/cancel", transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void returnsNotFoundWhenCancellingTransferOutsideSenderScope() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferService.cancel(SENDER.userId(), transferId))
                .thenThrow(new BusinessException(TransferErrorCode.NOT_FOUND));

        mvc(SENDER).perform(post("/api/v1/transfers/{transferId}/cancel", transferId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void returnsConflictWhenTransferCannotBeCancelled() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferService.cancel(SENDER.userId(), transferId))
                .thenThrow(new BusinessException(CommonErrorCode.CONFLICT));

        mvc(SENDER).perform(post("/api/v1/transfers/{transferId}/cancel", transferId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMMON_CONFLICT"));
    }

    @Test
    void requiresAuthenticatedUserToCancel() throws Exception {
        mvc(null).perform(post("/api/v1/transfers/{transferId}/cancel", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
        verifyNoInteractions(transferService);
    }

    @Test
    void returnsRecipientWithOnlyTransferableFlag() throws Exception {
        when(transferService.findRecipient(SENDER.userId(), RECEIVER_ID))
                .thenReturn(new RecipientResponse(RECEIVER_ID, true));

        mvc(SENDER).perform(get("/api/v1/transfers/recipients/{receiverId}", RECEIVER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.receiverId").value(RECEIVER_ID.toString()))
                .andExpect(jsonPath("$.data.transferable").value(true))
                .andExpect(jsonPath("$.data.walletId").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void returnsNotFoundForMissingRecipient() throws Exception {
        when(transferService.findRecipient(SENDER.userId(), RECEIVER_ID))
                .thenThrow(new BusinessException(TransferErrorCode.RECEIVER_NOT_FOUND));

        mvc(SENDER).perform(get("/api/v1/transfers/recipients/{receiverId}", RECEIVER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("TRANSFER_RECEIVER_NOT_FOUND"));
    }

    @Test
    void rejectsMalformedRecipientId() throws Exception {
        mvc(SENDER).perform(get("/api/v1/transfers/recipients/{receiverId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        verifyNoInteractions(transferService);
    }

    @Test
    void requiresAuthenticatedUserToFindRecipient() throws Exception {
        mvc(null).perform(get("/api/v1/transfers/recipients/{receiverId}", RECEIVER_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
        verifyNoInteractions(transferService);
    }

    @Test
    void returnsServiceUnavailableWhenWalletServiceIsDown() throws Exception {
        when(transferService.findRecipient(SENDER.userId(), RECEIVER_ID))
                .thenThrow(new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE));

        mvc(SENDER).perform(get("/api/v1/transfers/recipients/{receiverId}", RECEIVER_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("COMMON_SERVICE_UNAVAILABLE"));
    }

    @Test
    void listsTransfersWithCommonPageResponse() throws Exception {
        UUID transferId = UUID.randomUUID();
        var item = new TransferResponse(transferId, TransferDirection.RECEIVED, RECEIVER_ID, SENDER.userId(),
                10_000L, "KRW", TransferStatus.REQUESTED, null, Instant.parse("2026-10-28T01:02:03Z"));
        when(transferService.list(SENDER.userId(), TransferDirection.RECEIVED, 0, 20))
                .thenReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        mvc(SENDER).perform(get("/api/v1/transfers").param("direction", "RECEIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.data.items[0].direction").value("RECEIVED"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));
    }

    @Test
    void rejectsUnknownDirectionFilter() throws Exception {
        mvc(SENDER).perform(get("/api/v1/transfers").param("direction", "ALL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        verifyNoInteractions(transferService);
    }

    private MockMvc mvc(AuthenticatedUser user) {
        CurrentUserProvider provider = () -> Optional.ofNullable(user);
        return MockMvcBuilders.standaloneSetup(new TransferController(transferService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(provider))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
