package com.travelpay.exchange.rate.provider;

import java.time.LocalDate;
import java.util.List;

import com.travelpay.exchange.rate.domain.RateSource;

/**
 * 외부 환율 데이터를 가져옵니다.
 * 구현체는 exchange.rate.mode 설정으로 하나만 등록되며, 수집·저장·조회 로직은 이 인터페이스에만 의존합니다.
 */
public interface ExchangeRateProvider {

    RateSource source();

    /**
     * 지정한 날짜의 지원 통화 환율을 가져옵니다.
     * 고시 전이거나 휴일이라 데이터가 없으면 빈 목록을 반환합니다.
     *
     * @throws ExchangeRateFetchException 외부 호출이나 응답 해석에 실패한 경우
     */
    List<CollectedRate> fetch(LocalDate date);
}
