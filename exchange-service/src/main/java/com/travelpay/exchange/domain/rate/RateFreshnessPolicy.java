package com.travelpay.exchange.domain.rate;

import java.time.Instant;
import java.time.LocalDate;

/** 모드별로 저장된 환율이 견적에 쓸 수 있을 만큼 최신인지 판단합니다. */
public interface RateFreshnessPolicy {

    boolean isFresh(LocalDate rateDate, Instant collectedAt, Instant now);
}
