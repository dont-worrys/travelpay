package com.travelpay.exchange.presentation.limit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUserArgumentResolver;
import com.travelpay.common.error.GlobalExceptionHandler;
import com.travelpay.exchange.application.limit.ExchangeLimit;
import com.travelpay.exchange.application.limit.ExchangeLimitQueryService;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.travelpay.exchange.domain.quote.QuoteFixtures.range;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExchangeLimitControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final ExchangeLimitQueryService limitQueryService = mock(ExchangeLimitQueryService.class);
    private Optional<AuthenticatedUser> currentUser = Optional.of(new AuthenticatedUser(USER_ID, AuthRole.USER));
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ExchangeLimitController(limitQueryService))
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver(() -> currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    /** 금액은 통화 단위 문자열입니다. CNY 최소 단위 1000은 "10.00"입니다. */
    @Test
    void returnsLimitsAndAvailableAmountsAsCurrencyUnitStrings() throws Exception {
        when(limitQueryService.findAll(USER_ID)).thenReturn(List.of(
                new ExchangeLimit(range(Currency.KRW, 1000, 2_000_000), krw(500_000), krw(495_049)),
                new ExchangeLimit(range(Currency.JPY, 100, 200_000), new Money(Currency.JPY, 0),
                        new Money(Currency.JPY, 0)),
                new ExchangeLimit(range(Currency.CNY, 1000, 1_000_000), new Money(Currency.CNY, 10151),
                        new Money(Currency.CNY, 10050))));

        mockMvc.perform(get("/api/v1/exchange-limits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].currency").value("KRW"))
                .andExpect(jsonPath("$.data[0].minAmount").value("1000"))
                .andExpect(jsonPath("$.data[0].maxAmount").value("2000000"))
                .andExpect(jsonPath("$.data[0].balance").value("500000"))
                .andExpect(jsonPath("$.data[0].availableAmount").value("495049"))
                .andExpect(jsonPath("$.data[1].currency").value("JPY"))
                .andExpect(jsonPath("$.data[1].balance").value("0"))
                .andExpect(jsonPath("$.data[1].availableAmount").value("0"))
                .andExpect(jsonPath("$.data[2].currency").value("CNY"))
                .andExpect(jsonPath("$.data[2].minAmount").value("10.00"))
                .andExpect(jsonPath("$.data[2].maxAmount").value("10000.00"))
                .andExpect(jsonPath("$.data[2].balance").value("101.51"))
                .andExpect(jsonPath("$.data[2].availableAmount").value("100.50"));
    }

    /** 지갑 잔액을 가져오지 못하면 두 필드는 빠지지 않고 null로 내려갑니다. */
    @Test
    void returnsNullBalanceWhenWalletCannotBeRead() throws Exception {
        when(limitQueryService.findAll(USER_ID)).thenReturn(List.of(
                ExchangeLimit.withoutBalance(range(Currency.KRW, 1000, 2_000_000))));

        mockMvc.perform(get("/api/v1/exchange-limits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].maxAmount").value("2000000"))
                .andExpect(jsonPath("$.data[0].balance").isEmpty())
                .andExpect(jsonPath("$.data[0].availableAmount").isEmpty())
                .andExpect(content().string(containsString("\"balance\":null")));
    }

    private static Money krw(long amount) {
        return new Money(Currency.KRW, amount);
    }

    @Test
    void rejectsRequestWithoutCurrentUser() throws Exception {
        currentUser = Optional.empty();

        mockMvc.perform(get("/api/v1/exchange-limits"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));

        verifyNoInteractions(limitQueryService);
    }
}
