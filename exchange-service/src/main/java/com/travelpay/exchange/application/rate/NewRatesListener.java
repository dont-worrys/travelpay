package com.travelpay.exchange.application.rate;

import java.util.List;

import com.travelpay.exchange.domain.rate.ExchangeRate;

/**
 * 수집기가 새 환율을 저장(커밋)한 뒤 알림을 받습니다. 예약 환전 체결처럼 환율이 바뀔 때 할 일이 있는 쪽이 구현합니다.
 * 수집기는 누가 듣는지 모르고, 리스너가 실패해도 수집은 성공으로 끝납니다.
 * 수집 스레드에서 바로 부르므로, 오래 걸리면 다음 수집이 그만큼 늦어집니다.
 */
public interface NewRatesListener {

    /** @param saved 이번에 새로 저장된 환율. 비어 있으면 부르지 않습니다 */
    void onNewRates(List<ExchangeRate> saved);
}
