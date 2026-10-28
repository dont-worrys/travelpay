package com.travelpay.exchange.application.rate;

import java.util.List;
import java.util.Optional;

import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import com.travelpay.exchange.domain.rate.ExchangeRateRepository;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저장된 통화별 최신 환율을 조회합니다. 외부 API는 호출하지 않습니다.
 * 현재 모드의 출처 환율만 조회해, 다른 모드에서 쌓인 데이터(예: 이전 MOCK 실행분)가 섞이지 않게 합니다.
 */
@Service
public class ExchangeRateQueryService {

    private final ExchangeRateRepository repository;
    private final RateSource activeSource;

    public ExchangeRateQueryService(ExchangeRateRepository repository, ExchangeRateProvider provider) {
        this.repository = repository;
        // Provider에서는 현재 모드의 출처만 확인하고, fetch(외부 호출)는 하지 않습니다.
        this.activeSource = provider.source();
    }

    /** 지정한 통화별 최신 환율 목록입니다. 아직 수집된 환율이 없는 통화는 목록에서 빠집니다. */
    @Transactional(readOnly = true)
    public List<ExchangeRate> findLatest(List<ForeignCurrency> currencies) {
        return currencies.stream()
                .map(currency -> repository.findLatest(activeSource, currency))
                .flatMap(Optional::stream)
                .toList();
    }
}
