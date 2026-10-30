package com.travelpay.exchange.infrastructure.external.koreaexim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 한국수출입은행 환율 API 응답 한 건입니다. 숫자는 쉼표가 포함된 문자열로 내려옵니다. */
@JsonIgnoreProperties(ignoreUnknown = true)
record KoreaEximRateResponse(
        @JsonProperty("result") Integer result,
        @JsonProperty("cur_unit") String curUnit,
        @JsonProperty("deal_bas_r") String dealBaseRate,
        @JsonProperty("tts") String tts,
        @JsonProperty("ttb") String ttb
) {
}
