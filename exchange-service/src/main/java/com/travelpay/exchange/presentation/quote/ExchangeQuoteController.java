package com.travelpay.exchange.presentation.quote;

import java.util.UUID;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.exchange.application.quote.ExchangeQuoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exchange-quotes")
@RequiredArgsConstructor
public class ExchangeQuoteController {

    private final ExchangeQuoteService quoteService;

    /** 보내는 금액 기준으로 견적을 발급합니다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ExchangeQuoteResponse> issue(@CurrentUser AuthenticatedUser user,
                                                    @Valid @RequestBody ExchangeQuoteRequest request) {
        return ApiResponse.success(ExchangeQuoteResponse.from(quoteService.issue(request.toCommand(user.userId()))));
    }

    /** 본인 견적을 조회합니다. 다른 사람의 견적은 없는 견적과 같게 404입니다. */
    @GetMapping("/{quoteId}")
    public ApiResponse<ExchangeQuoteResponse> get(@CurrentUser AuthenticatedUser user, @PathVariable UUID quoteId) {
        return ApiResponse.success(ExchangeQuoteResponse.from(quoteService.find(user.userId(), quoteId)));
    }
}
