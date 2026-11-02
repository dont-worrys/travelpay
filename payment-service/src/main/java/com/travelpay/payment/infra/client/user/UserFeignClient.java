package com.travelpay.payment.infra.client.user;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.application.merchant.client.MerchantOwnerClient;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserFeignClient implements MerchantOwnerClient {

    private static final String USER_NOT_FOUND = "USER_RECIPIENT_NOT_FOUND";

    private final UserFeignApi userFeignApi;

    @Override
    public boolean isActiveUser(UUID userId) {
        ApiResponse<UserFeignApi.UserResponse> response;
        try {
            response = userFeignApi.getActiveUser(userId);
        } catch (FeignException exception) {
            // 경로 오류 등으로 생기는 다른 404는 회원 없음이 아니므로 USER_RECIPIENT_NOT_FOUND일 때만 없는 회원으로 본다
            if (FeignErrors.hasErrorCode(exception, USER_NOT_FOUND)) {
                return false;
            }
            log.error("회원 조회 실패 userId={} status={}", userId, exception.status());
            throw FeignErrors.toCommonException(exception);
        }

        if (response == null || !response.success() || response.data() == null
                || !userId.equals(response.data().userId())) {
            log.error("회원 조회 응답 형식 이상 userId={}", userId);
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY);
        }
        return true;
    }
}
