package com.travelpay.exchange.domain.rate;

/**
 * 하루 한 번 고시하는 출처의 같은 통화·고시일 환율이 이미 저장된 경우입니다.
 * 다른 인스턴스가 같은 고시분을 먼저 저장했다는 뜻이므로, 수집 쪽에서는 정상 흐름으로 다룹니다.
 */
public class DuplicateExchangeRateException extends RuntimeException {

    public DuplicateExchangeRateException(String message) {
        super(message);
    }
}
