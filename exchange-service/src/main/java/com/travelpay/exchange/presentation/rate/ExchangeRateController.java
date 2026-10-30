package com.travelpay.exchange.presentation.rate;

import java.util.List;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.exchange.application.rate.ExchangeRateQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exchange-rates")
@RequiredArgsConstructor
public class ExchangeRateController {

    private final ExchangeRateQueryService queryService;

    /** 통화별 최신 환율을 조회합니다. currency를 지정해도 응답은 항상 목록입니다. */
    @GetMapping
    public ApiResponse<List<ExchangeRateResponse>> getLatestRates(@ModelAttribute ExchangeRateSearchRequest request) {
        List<ExchangeRateResponse> rates = queryService.findLatest(request.targetCurrencies()).stream()
                .map(ExchangeRateResponse::from)
                .toList();
        return ApiResponse.success(rates);
    }
}
