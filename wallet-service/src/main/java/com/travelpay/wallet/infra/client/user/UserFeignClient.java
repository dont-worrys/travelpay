package com.travelpay.wallet.infra.client.user;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

/**
 * 회원 내부 API. url을 비우면 Eureka에서 user-service를 찾는다.
 * TODO: 서비스 간 내부 인증 헤더 규칙이 정해지면 추가
 */
@FeignClient(name = "user-service", url = "${travelpay.client.user.url:}",
        path = "/internal/v1/users", configuration = UserFeignConfig.class)
public interface UserFeignClient {

    @GetMapping("/{userId}/real-name")
    UserApiResponse<UserRealNameResponse> getRealName(@PathVariable("userId") UUID userId);
}
