package com.travelpay.payment.infra.client.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.travelpay.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(name = "user-service", contextId = "paymentUserApi", url = "${travelpay.client.user.url:}",
        path = "/internal/v1/users")
public interface UserFeignApi {

    @GetMapping("/{userId}/real-name")
    ApiResponse<UserResponse> getActiveUser(@PathVariable("userId") UUID userId);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserResponse(UUID userId) {
    }
}
