package com.travelpay.exchange.domain.rate;

/** 외부 환율 데이터를 가져오지 못한 경우입니다. 수집 스케줄러는 다음 주기에 다시 시도합니다. */
public class ExchangeRateFetchException extends RuntimeException {

    public ExchangeRateFetchException(String message) {
        super(message);
    }
}
