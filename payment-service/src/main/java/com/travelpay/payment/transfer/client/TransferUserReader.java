package com.travelpay.payment.transfer.client;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 수취인이 송금 받을 수 있는 회원(ACTIVE)인지 user-service에서 확인합니다.
 * 오류 해석 기준은 {@link TransferWalletReader}와 같습니다.
 */
@Component
public class TransferUserReader {

    private static final String RECIPIENT_NOT_FOUND = "USER_RECIPIENT_NOT_FOUND";

    private final UserClient userClient;
    private final JsonMapper jsonMapper;

    public TransferUserReader(UserClient userClient, JsonMapper jsonMapper) {
        this.userClient = userClient;
        this.jsonMapper = jsonMapper;
    }

    /**
     * ACTIVE 회원이면 회원 정보를, 없는 회원이거나 정지·종료 회원이면 empty를 반환합니다(user-service가 둘을 구분하지 않음).
     * 경로 오류·배포 문제로 생기는 404는 수취인 없음이 아니므로 USER_RECIPIENT_NOT_FOUND일 때만 empty로 봅니다.
     */
    public Optional<UserRecipientResponse> findRecipient(UUID userId) {
        ApiResponse<UserRecipientResponse> response;
        try {
            response = userClient.getRecipient(userId);
        } catch (FeignException.NotFound exception) {
            if (isRecipientNotFound(exception)) {
                return Optional.empty();
            }
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY, exception);
        } catch (RetryableException | FeignException.ServiceUnavailable exception) {
            // 연결·응답 시간 문제, Eureka 미등록 시 로드밸런서의 503, user-service 자체 503은 일시적 장애로 알립니다.
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        } catch (FeignException exception) {
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY, exception);
        }

        if (response == null || !response.success() || response.data() == null
                || response.data().realName() == null || response.data().realName().isBlank()) {
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY);
        }
        return Optional.of(response.data());
    }

    private boolean isRecipientNotFound(FeignException exception) {
        try {
            ErrorBody body = jsonMapper.readValue(exception.contentUTF8(), ErrorBody.class);
            return body != null && body.error() != null && RECIPIENT_NOT_FOUND.equals(body.error().code());
        } catch (JacksonException | IllegalArgumentException ignored) {
            return false;
        }
    }

    /** 404 응답에서 오류 코드만 읽습니다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(ErrorCodeBody error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorCodeBody(String code) {
    }
}
