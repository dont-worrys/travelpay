package com.travelpay.payment.transfer.client;

import java.util.UUID;

import com.travelpay.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * user-service의 내부 수취인 확인 API(명세 I02)입니다.
 * ACTIVE 회원이면 200과 회원 ID·가입 성명을 주고, 없는 회원과 정지·종료 회원은 상태를 숨기려고 같은 404(USER_RECIPIENT_NOT_FOUND)로 응답합니다.
 * 팀 결정(2026-10-31)에 따라 서비스 인증 헤더 없이 내부망 격리로 보호합니다.
 * 송금 요청 처리 중에 호출하므로 common 인터셉터가 송금인의 User-Id·User-Role·Request-Id를 붙이며, user-service는 이 헤더를 읽지 않습니다.
 */
@FeignClient(name = "user-service", path = "/internal/v1/users")
public interface UserClient {

    @GetMapping("/{userId}/real-name")
    ApiResponse<UserRecipientResponse> getRecipient(@PathVariable("userId") UUID userId);
}
