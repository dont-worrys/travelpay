package com.travelpay.exchange.application.rate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.travelpay.exchange.domain.rate.CollectedRate;
import com.travelpay.exchange.domain.rate.DuplicateExchangeRateException;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ExchangeRateFetchException;
import com.travelpay.exchange.domain.rate.ExchangeRateProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 외부 환율을 가져와 저장합니다. 모드별 차이는 Provider와 정책이 결정하며, 이 클래스는 모드를 알지 못합니다.
 * 외부 호출 실패는 다음 주기에 다시 시도하도록 로그만 남깁니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeRateCollector {

    /** 저장된 환율이 없을 때 최근 고시일을 찾아 거슬러 올라가는 최대 일수입니다. (긴 연휴 포함) */
    static final int INITIAL_LOOKBACK_DAYS = 10;

    private final ExchangeRateProvider provider;
    private final RateCollectionPolicy policy;
    private final ExchangeRateStore store;
    private final Clock clock;

    /**
     * 스케줄러가 깨어날 때마다 호출됩니다. 오늘 환율을 가져올지 판단합니다.
     * 저장된 환율이 없으면 모드와 관계없이 수집하고, 있으면 정책이 판단합니다.
     */
    public void collectIfNeeded() {
        LocalDate today = LocalDate.now(clock);
        Optional<LocalDate> latestRateDate = store.latestRateDate(provider.source());
        if (latestRateDate.isPresent() && !policy.shouldCollect(today, latestRateDate.get())) {
            return;
        }
        fetch(today).ifPresent(rates -> save(today, rates));
    }

    /**
     * 저장된 환율이 하나도 없으면 오늘부터 거슬러 올라가며 가장 최근 고시분을 한 번 저장합니다.
     * 서버를 처음 띄웠을 때 고시 전이거나 휴일이어도 조회할 환율이 있게 합니다.
     */
    public void collectLatestIfEmpty() {
        if (store.latestRateDate(provider.source()).isPresent()) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        for (int daysAgo = 0; daysAgo < INITIAL_LOOKBACK_DAYS; daysAgo++) {
            LocalDate date = today.minusDays(daysAgo);
            Optional<List<CollectedRate>> rates = fetch(date);
            if (rates.isEmpty()) {
                return;
            }
            if (!rates.get().isEmpty()) {
                save(date, rates.get());
                return;
            }
        }
        log.warn("최근 {}일 동안 고시된 환율을 찾지 못했습니다. source={}", INITIAL_LOOKBACK_DAYS, provider.source());
    }

    /** 외부 호출에 실패하면 empty를 반환합니다. 고시 전·휴일이면 빈 목록입니다. */
    private Optional<List<CollectedRate>> fetch(LocalDate date) {
        try {
            return Optional.of(provider.fetch(date));
        } catch (ExchangeRateFetchException e) {
            log.warn("환율 수집 실패: source={}, date={}, reason={}", provider.source(), date, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 가져온 환율을 저장합니다. 고시 전·휴일이라 비어 있으면 저장하지 않습니다.
     * 다른 인스턴스가 먼저 저장해 하루 1건 유니크 인덱스에 막히면 이미 저장된 것으로 보고 넘어갑니다.
     * 그 밖의 저장 실패는 숨기지 않고 던집니다. (스케줄러가 오류 로그를 남기고 다음 주기에 재시도)
     */
    private void save(LocalDate date, List<CollectedRate> rates) {
        if (rates.isEmpty()) {
            log.info("고시된 환율이 아직 없습니다: source={}, date={}", provider.source(), date);
            return;
        }
        try {
            List<ExchangeRate> saved = store.saveNew(rates, clock.instant());
            log.info("환율 저장: source={}, date={}, fetched={}, saved={}",
                    provider.source(), date, rates.size(), saved.size());
        } catch (DuplicateExchangeRateException e) {
            // 다른 인스턴스가 같은 고시분을 먼저 저장했습니다. 이미 저장된 상태이므로 정상 흐름입니다.
            log.info("다른 수집이 먼저 저장해 건너뜁니다: source={}, date={}", provider.source(), date);
        }
    }
}
