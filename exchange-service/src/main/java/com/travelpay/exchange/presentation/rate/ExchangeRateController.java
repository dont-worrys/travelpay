package com.travelpay.exchange.presentation.rate;

import java.util.List;
import java.util.Optional;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.exchange.application.rate.ExchangeRateQueryService;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exchange-rates")
public class ExchangeRateController {

    private final ExchangeRateQueryService queryService;

    public ExchangeRateController(ExchangeRateQueryService queryService) {
        this.queryService = queryService;
    }

    /** 통화별 최신 환율을 조회합니다. currency를 지정해도 응답은 항상 목록입니다. */
    @GetMapping
    public ApiResponse<List<ExchangeRateResponse>> getLatestRates(
            @RequestParam(required = false) String currency
    ) {
        Optional<ForeignCurrency> target = Optional.ofNullable(currency).map(ForeignCurrency::fromCode);
        List<ExchangeRateResponse> rates = queryService.findLatest(target).stream()
                .map(ExchangeRateResponse::from)
                .toList();
        return ApiResponse.success(rates);
    }
}
