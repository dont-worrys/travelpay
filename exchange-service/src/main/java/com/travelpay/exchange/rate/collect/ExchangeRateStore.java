package com.travelpay.exchange.rate.collect;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.travelpay.exchange.rate.domain.ExchangeRate;
import com.travelpay.exchange.rate.domain.ExchangeRateRepository;
import com.travelpay.exchange.rate.domain.ForeignCurrency;
import com.travelpay.exchange.rate.domain.RateSource;
import com.travelpay.exchange.rate.policy.RateCollectionPolicy;
import com.travelpay.exchange.rate.provider.CollectedRate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 수집한 환율을 저장하고, 수집 판단에 필요한 최신 고시일을 조회합니다. */
@Component
public class ExchangeRateStore {

    private final ExchangeRateRepository repository;
    private final RateCollectionPolicy policy;

    public ExchangeRateStore(ExchangeRateRepository repository, RateCollectionPolicy policy) {
        this.repository = repository;
        this.policy = policy;
    }

    /**
     * 아직 저장되지 않은 환율만 저장합니다.
     * 하루 한 번 고시하는 출처는 같은 통화·고시일이 이미 있으면 건너뜁니다.
     */
    @Transactional
    public List<ExchangeRate> saveNew(List<CollectedRate> rates, Instant collectedAt) {
        List<ExchangeRate> saved = new ArrayList<>();
        for (CollectedRate rate : rates) {
            if (policy.publishesOncePerDay()
                    && repository.existsBySourceAndCurrencyAndRateDate(rate.source(), rate.currency(), rate.rateDate())) {
                continue;
            }
            saved.add(repository.save(ExchangeRate.collected(rate, collectedAt)));
        }
        return saved;
    }

    /**
     * 지원 통화 모두에 대해 저장된 최신 고시일 중 가장 이른 날짜입니다.
     * 한 통화라도 저장된 환율이 없으면 empty를 반환해 다시 수집하게 합니다.
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> latestRateDate(RateSource source) {
        LocalDate earliest = null;
        for (ForeignCurrency currency : ForeignCurrency.values()) {
            Optional<ExchangeRate> latest =
                    repository.findFirstBySourceAndCurrencyOrderByRateDateDescCollectedAtDesc(source, currency);
            if (latest.isEmpty()) {
                return Optional.empty();
            }
            LocalDate rateDate = latest.get().getRateDate();
            if (earliest == null || rateDate.isBefore(earliest)) {
                earliest = rateDate;
            }
        }
        return Optional.ofNullable(earliest);
    }
}
