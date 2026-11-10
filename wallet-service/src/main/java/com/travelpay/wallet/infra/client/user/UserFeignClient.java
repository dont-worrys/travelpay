package com.travelpay.wallet.infra.client.user;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

/**
 * 회원 내부 API. url을 비우면 Eureka에서 user-service를 찾는다.
 * User-Id·User-Role은 요청자 정보다. 회원 서비스가 User-Id와 경로 userId가 같은지 본인 확인에 쓴다.
 */
@FeignClient(name = "user-service", url = "${travelpay.client.user.url:}",
        path = "/internal/v1/users", configuration = UserFeignConfig.class)
public interface UserFeignClient {

    @GetMapping("/{userId}/funding-identity")
    ApiResponse<UserFundingIdentityResponse> getFundingIdentity(
            @PathVariable("userId") UUID userId,
            @RequestHeader(AuthHeaders.USER_ID) UUID requesterId,
            @RequestHeader(AuthHeaders.USER_ROLE) String requesterRole);
}
