package com.travelpay.exchange.infrastructure.external.koreaexim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.ExchangeRateFetchException;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 한국수출입은행 환율 API에서 영업일 오전에 고시되는 환율을 가져옵니다.
 * 엔화는 100엔 단위(JPY(100))로, 위안화는 CNH 코드로 내려오므로 우리 통화 코드와 단위로 바꿉니다.
 */
public class KoreaEximRateProvider implements ExchangeRateProvider {

    private static final DateTimeFormatter SEARCH_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern CUR_UNIT = Pattern.compile("([A-Z]{3})(?:\\((\\d+)\\))?");
    private static final Map<String, ForeignCurrency> SUPPORTED_CODES = Map.of(
            "JPY", ForeignCurrency.JPY,
            "CNH", ForeignCurrency.CNY
    );
    private static final int RESULT_SUCCESS = 1;
    private static final Map<Integer, String> RESULT_ERRORS = Map.of(
            2, "DATA 코드 오류",
            3, "인증 코드 오류",
            4, "일일 호출 한도 초과"
    );
    private static final TypeReference<List<KoreaEximRateResponse>> RESPONSE_TYPE = new TypeReference<>() {
    };
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient restClient;
    private final String apiKey;

    public KoreaEximRateProvider(RestClient restClient, String apiKey) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
    }

    @Override
    public RateSource source() {
        return RateSource.KOREAEXIM;
    }

    @Override
    public List<CollectedRate> fetch(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return parse(request(date), date);
    }

    /** 지정한 고시일의 환율 응답 본문을 받아옵니다. 호출 실패 메시지에서는 인증키를 가립니다. */
    private String request(LocalDate date) {
        try {
            // 응답 Content-Type을 신뢰하지 않고 본문을 직접 해석합니다.
            return restClient.get()
                    .uri(builder -> builder
                            .queryParam("authkey", apiKey)
                            .queryParam("searchdate", date.format(SEARCH_DATE))
                            .queryParam("data", "AP01")
                            .build())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // 예외 메시지에 요청 URL(인증키 포함)이 들어가므로 키를 가리고, 원인 예외도 연결하지 않습니다.
            throw callFailed(mask(e.getMessage()));
        }
    }

    /**
     * 응답 본문을 지원 통화의 환율 목록으로 바꿉니다. 지원하지 않는 통화는 건너뜁니다.
     * 본문이 비어 있으면 고시 전·휴일로 보고 빈 목록을, result 코드가 오류면 예외를 던집니다.
     */
    private List<CollectedRate> parse(String body, LocalDate date) {
        if (body == null || body.isBlank()) {
            return List.of();
        }

        List<KoreaEximRateResponse> responses;
        try {
            responses = JSON.readValue(body, RESPONSE_TYPE);
        } catch (JacksonException e) {
            throw unreadableResponse(e.getOriginalMessage());
        }

        List<CollectedRate> rates = new ArrayList<>();
        for (KoreaEximRateResponse response : responses) {
            if (response.result() != null && response.result() != RESULT_SUCCESS) {
                throw apiError(response.result());
            }
            toCollectedRate(response, date).ifPresent(rates::add);
        }
        return List.copyOf(rates);
    }

    /**
     * 응답 한 건을 우리 통화 코드와 단위로 바꿉니다. 예: JPY(100)은 JPY·100, CNH는 CNY·1입니다.
     * 지원하지 않는 통화면 empty를, 환율 값이 비었거나 숫자가 아니면 예외를 던집니다.
     */
    private Optional<CollectedRate> toCollectedRate(KoreaEximRateResponse response, LocalDate date) {
        if (response.curUnit() == null) {
            return Optional.empty();
        }
        Matcher matcher = CUR_UNIT.matcher(response.curUnit().trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        ForeignCurrency currency = SUPPORTED_CODES.get(matcher.group(1));
        if (currency == null) {
            return Optional.empty();
        }
        int unit = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));

        try {
            return Optional.of(new CollectedRate(
                    currency,
                    unit,
                    toDecimal(response.dealBaseRate()),
                    toDecimal(response.tts()),
                    toDecimal(response.ttb()),
                    date,
                    RateSource.KOREAEXIM
            ));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalidRate(response.curUnit(), e.getMessage());
        }
    }

    private static BigDecimal toDecimal(String value) {
        Objects.requireNonNull(value, "rate");
        return new BigDecimal(value.replace(",", "").trim());
    }

    /** 예외 메시지에 들어간 인증키를 가립니다. */
    private String mask(String message) {
        if (message == null || apiKey.isEmpty()) {
            return message;
        }
        return message.replace(apiKey, "****");
    }

    private static ExchangeRateFetchException callFailed(String reason) {
        return new ExchangeRateFetchException("수출입은행 환율 API 호출 실패: " + reason);
    }

    private static ExchangeRateFetchException unreadableResponse(String reason) {
        return new ExchangeRateFetchException("수출입은행 환율 응답 해석 실패: " + reason);
    }

    private static ExchangeRateFetchException apiError(int result) {
        return new ExchangeRateFetchException("수출입은행 환율 API 오류: result=" + result
                + " (" + RESULT_ERRORS.getOrDefault(result, "알 수 없는 오류") + ")");
    }

    private static ExchangeRateFetchException invalidRate(String curUnit, String reason) {
        return new ExchangeRateFetchException("수출입은행 환율 값이 올바르지 않습니다: " + curUnit + " " + reason);
    }
}
