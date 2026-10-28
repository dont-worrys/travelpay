package com.travelpay.exchange.infrastructure.external.koreaexim;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRateFetchException;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KoreaEximRateProviderTest {

    private static final String BASE_URL = "https://koreaexim.test/exchangeJSON";
    private static final String API_KEY = "test-secret-key";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 28);
    private static final String EXPECTED_URL = BASE_URL + "?authkey=" + API_KEY + "&searchdate=20261028&data=AP01";

    private MockRestServiceServer server;
    private KoreaEximRateProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new KoreaEximRateProvider(builder.build(), API_KEY);
    }

    @Test
    void mapsJpyPer100AndCnhToSupportedCurrenciesOnly() {
        respondWith("""
                [
                  {"result":1,"cur_unit":"USD","ttb":"1,372.33","tts":"1,400.06","deal_bas_r":"1,386.2","cur_nm":"미국 달러"},
                  {"result":1,"cur_unit":"JPY(100)","ttb":"896.07","tts":"914.18","deal_bas_r":"905.12","cur_nm":"일본 옌"},
                  {"result":1,"cur_unit":"CNH","ttb":"188.45","tts":"192.25","deal_bas_r":"190.35","cur_nm":"위안화"}
                ]
                """);

        List<CollectedRate> rates = provider.fetch(DATE);

        assertEquals(List.of(
                new CollectedRate(ForeignCurrency.JPY, 100,
                        new BigDecimal("905.12"), new BigDecimal("914.18"), new BigDecimal("896.07"),
                        DATE, RateSource.KOREAEXIM),
                new CollectedRate(ForeignCurrency.CNY, 1,
                        new BigDecimal("190.35"), new BigDecimal("192.25"), new BigDecimal("188.45"),
                        DATE, RateSource.KOREAEXIM)
        ), rates);
        server.verify();
    }

    @Test
    void parsesRatesContainingThousandsSeparators() {
        respondWith("""
                [{"result":1,"cur_unit":"JPY(100)","ttb":"1,001.5","tts":"1,021.7","deal_bas_r":"1,011.6"}]
                """);

        CollectedRate rate = provider.fetch(DATE).getFirst();

        assertEquals(new BigDecimal("1011.6"), rate.midRate());
        assertEquals(new BigDecimal("1021.7"), rate.buyRate());
        assertEquals(new BigDecimal("1001.5"), rate.sellRate());
    }

    @Test
    void returnsEmptyListWhenRatesAreNotPublishedYet() {
        respondWith("[]");

        assertEquals(List.of(), provider.fetch(DATE));
    }

    @ParameterizedTest
    @CsvSource({
            "2, DATA 코드 오류",
            "3, 인증 코드 오류",
            "4, 일일 호출 한도 초과"
    })
    void rejectsApiErrorResults(int result, String description) {
        respondWith("[{\"result\":" + result + ",\"cur_unit\":null}]");

        var exception = assertThrows(ExchangeRateFetchException.class, () -> provider.fetch(DATE));

        assertTrue(exception.getMessage().contains("result=" + result));
        assertTrue(exception.getMessage().contains(description));
    }

    @Test
    void rejectsMalformedRateValues() {
        respondWith("""
                [{"result":1,"cur_unit":"JPY(100)","ttb":"-","tts":"914.18","deal_bas_r":"905.12"}]
                """);

        assertThrows(ExchangeRateFetchException.class, () -> provider.fetch(DATE));
    }

    @Test
    void rejectsBodyThatIsNotJson() {
        respondWith("<html>점검 중</html>");

        assertThrows(ExchangeRateFetchException.class, () -> provider.fetch(DATE));
    }

    @Test
    void doesNotExposeApiKeyWhenServerFails() {
        server.expect(requestTo(EXPECTED_URL)).andRespond(withServerError());

        var exception = assertThrows(ExchangeRateFetchException.class, () -> provider.fetch(DATE));

        assertFalse(exception.getMessage().contains(API_KEY));
        assertEquals(null, exception.getCause());
    }

    @Test
    void doesNotExposeApiKeyWhenConnectionFails() {
        server.expect(requestTo(EXPECTED_URL)).andRespond(withException(new IOException("connection refused")));

        var exception = assertThrows(ExchangeRateFetchException.class, () -> provider.fetch(DATE));

        assertFalse(exception.getMessage().contains(API_KEY));
        assertEquals(null, exception.getCause());
    }

    private void respondWith(String body) {
        server.expect(requestTo(EXPECTED_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
