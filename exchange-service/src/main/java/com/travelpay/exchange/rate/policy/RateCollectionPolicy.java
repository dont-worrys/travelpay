package com.travelpay.exchange.rate.policy;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.scheduling.Trigger;

/** 모드별 환율 수집 주기와 수집 여부를 정합니다. */
public interface RateCollectionPolicy {

    /** 수집 스케줄러가 깨어나는 시점입니다. */
    Trigger trigger();

    /**
     * 스케줄러가 깨어났을 때 실제로 외부 데이터를 가져올지 판단합니다.
     *
     * @param today          수집 기준 날짜
     * @param latestRateDate 이미 저장된 최신 고시일 (저장된 환율이 없으면 empty)
     */
    boolean shouldCollect(LocalDate today, Optional<LocalDate> latestRateDate);

    /**
     * 출처가 하루에 한 번만 고시하는지입니다.
     * true이면 같은 출처·통화·고시일의 환율은 한 건만 저장합니다.
     */
    boolean publishesOncePerDay();
}
