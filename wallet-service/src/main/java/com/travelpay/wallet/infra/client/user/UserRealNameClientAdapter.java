package com.travelpay.wallet.infra.client.user;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.wallet.application.charge.UserRealNameClient;
import com.travelpay.wallet.application.charge.UserResponseException;
import com.travelpay.wallet.domain.WalletErrorCode;
import feign.FeignException;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 회원 실명 응답 해석. 실명·응답 내용은 예외 메시지·로그에 싣지 않는다.
 * - 2xx: 비어 있지 않고 저장 가능한 길이의 실명만 사용
 * - 404: 회원 없음·비ACTIVE → CHARGE_USER_NOT_ACTIVE(409)
 * - 5xx·타임아웃·연결 실패: 503
 * - 그 밖의 4xx·형식 이상: UserResponseException(500)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserRealNameClientAdapter implements UserRealNameClient {

    /** charge_requests.holder_name_snapshot 길이 */
    private static final int REAL_NAME_MAX = 100;

    private final UserFeignClient feignClient;

    @Override
    public String findRealName(UUID userId) {
        UserApiResponse<UserRealNameResponse> response;
        try {
            response = feignClient.getRealName(userId);
        } catch (FeignException e) {
            throw classify(userId, e);
        }
        if (response == null || !response.success() || response.data() == null) {
            throw new UserResponseException();
        }
        String realName = response.data().realName();
        if (realName == null || realName.isBlank() || realName.strip().length() > REAL_NAME_MAX) {
            throw new UserResponseException();
        }
        return realName.strip();
    }

    private RuntimeException classify(UUID userId, FeignException e) {
        if (e instanceof RetryableException || e.status() >= 500) {
            return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, e);
        }
        // TODO: 회원 서비스의 error.code가 확정되면 그 코드일 때만 CHARGE_USER_NOT_ACTIVE로 좁히고, 나머지 404는 UserResponseException으로 보낼 것
        if (e.status() == 404) {
            log.warn("실명 조회 대상 회원 없음 또는 비활성 userId={}", userId);
            return new BusinessException(WalletErrorCode.CHARGE_USER_NOT_ACTIVE, e);
        }
        return new UserResponseException(e);
    }
}
