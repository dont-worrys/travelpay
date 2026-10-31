package com.travelpay.exchange.presentation.limit;

import java.util.List;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.exchange.application.limit.ExchangeLimitQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exchange-limits")
@RequiredArgsConstructor
public class ExchangeLimitController {

    private final ExchangeLimitQueryService limitQueryService;

    /** 보내는 통화별 1회 환전 한도와, 로그인한 사용자의 지갑 잔액으로 환전할 수 있는 금액을 조회합니다. */
    @GetMapping
    public ApiResponse<List<ExchangeLimitResponse>> getLimits(@CurrentUser AuthenticatedUser user) {
        return ApiResponse.success(limitQueryService.findAll(user.userId()).stream()
                .map(ExchangeLimitResponse::from)
                .toList());
    }
}
