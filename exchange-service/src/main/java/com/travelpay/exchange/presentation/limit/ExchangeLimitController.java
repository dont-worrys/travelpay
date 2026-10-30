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

    /**
     * 보내는 통화별 1회 환전 한도를 조회합니다.
     * 지금은 모든 사용자에게 같은 값이지만, 잔액 기반 한도로 넓힐 때 계약이 바뀌지 않도록 로그인한 사용자만 조회합니다.
     */
    @GetMapping
    public ApiResponse<List<ExchangeLimitResponse>> getLimits(@CurrentUser AuthenticatedUser user) {
        return ApiResponse.success(limitQueryService.findAll().stream()
                .map(ExchangeLimitResponse::from)
                .toList());
    }
}
