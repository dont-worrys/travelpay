package com.travelpay.wallet.infra.client.user;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.charge.UserFundingIdentityClient;
import com.travelpay.wallet.application.charge.UserResponseException;
import com.travelpay.wallet.domain.WalletErrorCode;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * 충전 예금주 확인용 회원 실명(funding-identity) 응답 해석. 실명·응답 내용은 예외 메시지·로그에 싣지 않는다.
 * - 403 + USER_ACCOUNT_SUSPENDED·USER_ACCOUNT_CLOSED, 404 + USER_NOT_FOUND: CHARGE_USER_NOT_ACTIVE(409)
 * - 호출 실패와 2xx 응답 해석 실패: FeignErrors.toCommonException (시간 초과·504 → 504, 연결 실패·503 → 503, 나머지 502).
 *   처리하지 않은 4xx와 2xx 응답 해석 실패는 ERROR로 남긴다
 * - 2xx 응답을 읽었으나 내용 이상(요청한 회원의 것이 아님, 실명 없음·저장 가능 길이 초과): UserResponseException(500)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserFundingIdentityClientAdapter implements UserFundingIdentityClient {

    /** charge_requests.holder_name_snapshot 길이 */
    private static final int REAL_NAME_MAX = 100;
    private static final Set<String> INACTIVE_CODES = Set.of("USER_ACCOUNT_SUSPENDED", "USER_ACCOUNT_CLOSED");
    private static final String USER_NOT_FOUND = "USER_NOT_FOUND";

    private final UserFeignClient feignClient;

    /** 충전은 회원(USER)만 요청하므로 요청자 역할은 USER로 보낸다 */
    @Override
    public String findRealName(UUID userId) {
        ApiResponse<UserFundingIdentityResponse> response;
        try {
            response = feignClient.getFundingIdentity(userId, userId, AuthRole.USER.name());
        } catch (FeignException e) {
            throw classify(userId, e);
        }
        if (response == null || !response.success() || response.data() == null
                || !userId.equals(response.data().userId())) {
            log.error("회원 실명 응답 형식 이상 userId={}", userId);
            throw new UserResponseException();
        }
        String realName = response.data().realName();
        if (realName == null || realName.isBlank() || realName.strip().length() > REAL_NAME_MAX) {
            log.error("회원 실명 값 이상 userId={}", userId);
            throw new UserResponseException();
        }
        return realName.strip();
    }

    private BusinessException classify(UUID userId, FeignException e) {
        String code = FeignErrors.errorCode(e).orElse(null);
        // 코드를 읽지 못하면 아래 비교를 건너뛴다 (Set.of는 contains(null)에서 NPE)
        if (code != null) {
            if (e.status() == 403 && INACTIVE_CODES.contains(code)) {
                return new BusinessException(WalletErrorCode.CHARGE_USER_NOT_ACTIVE, e);
            }
            if (e.status() == 404 && USER_NOT_FOUND.equals(code)) {
                log.warn("충전 실명 조회 대상 회원 없음 userId={}", userId);
                return new BusinessException(WalletErrorCode.CHARGE_USER_NOT_ACTIVE, e);
            }
        }
        if (FeignErrors.isRejected(e) || isSuccessStatus(e)) {
            log.error("회원 실명 응답 처리 불가 userId={} status={} code={}", userId, e.status(), code);
        }
        return FeignErrors.toCommonException(e);
    }

    /** 2xx인데 Feign 예외면 본문을 해석하지 못한 것이다 */
    private static boolean isSuccessStatus(FeignException e) {
        return e.status() >= 200 && e.status() < 300;
    }
}
