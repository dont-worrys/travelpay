package com.travelpay.wallet.infra.client.user;

import com.travelpay.common.auth.AuthRole;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;
import java.util.UUID;

/**
 * 충전 예금주 확인용 회원 실명(funding-identity) 응답 해석. 실명·응답 내용은 예외 메시지·로그에 싣지 않는다.
 * - 2xx: 요청한 회원의 것이고 비어 있지 않고 저장 가능한 길이의 실명만 사용
 * - 403 + USER_ACCOUNT_SUSPENDED·USER_ACCOUNT_CLOSED, 404 + USER_NOT_FOUND: CHARGE_USER_NOT_ACTIVE(409)
 * - 5xx·타임아웃·연결 실패: 503
 * - 그 밖의 4xx(경로 없음 404 포함)·형식 이상: UserResponseException(500)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserRealNameClientAdapter implements UserRealNameClient {

    /** charge_requests.holder_name_snapshot 길이 */
    private static final int REAL_NAME_MAX = 100;
    private static final Set<String> INACTIVE_CODES = Set.of("USER_ACCOUNT_SUSPENDED", "USER_ACCOUNT_CLOSED");
    private static final String USER_NOT_FOUND = "USER_NOT_FOUND";

    private final UserFeignClient feignClient;
    private final JsonMapper jsonMapper;

    /** 충전은 회원(USER)만 요청하므로 요청자 역할은 USER로 보낸다 */
    @Override
    public String findRealName(UUID userId) {
        UserApiResponse<UserFundingIdentityResponse> response;
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

    private RuntimeException classify(UUID userId, FeignException e) {
        if (e instanceof RetryableException || e.status() >= 500) {
            return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, e);
        }
        String code = errorCode(e);
        // 코드를 읽지 못하면 그 외 4xx로 본다 (Set.of는 contains(null)에서 NPE)
        if (code != null) {
            if (e.status() == 403 && INACTIVE_CODES.contains(code)) {
                return new BusinessException(WalletErrorCode.CHARGE_USER_NOT_ACTIVE, e);
            }
            if (e.status() == 404 && USER_NOT_FOUND.equals(code)) {
                log.warn("충전 실명 조회 대상 회원 없음 userId={}", userId);
                return new BusinessException(WalletErrorCode.CHARGE_USER_NOT_ACTIVE, e);
            }
        }
        log.error("회원 실명 응답 처리 불가 userId={} status={} code={}", userId, e.status(), code);
        return new UserResponseException(e);
    }

    /** 응답 본문의 error.code. 읽을 수 없으면 null */
    private String errorCode(FeignException e) {
        try {
            UserApiResponse<?> body = jsonMapper.readValue(e.contentUTF8(), UserApiResponse.class);
            return body != null && !body.success() && body.error() != null ? body.error().code() : null;
        } catch (JacksonException | IllegalArgumentException ignored) {
            return null;
        }
    }
}
