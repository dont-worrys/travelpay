package com.travelpay.exchange.domain.quote;

import java.util.Optional;
import java.util.UUID;

/** 견적 저장소입니다. 구현은 infrastructure 계층이 담당합니다. */
public interface ExchangeQuoteRepository {

    ExchangeQuote save(ExchangeQuote quote);

    /** 요청자 본인의 견적만 찾습니다. 다른 사람의 견적은 없는 것과 같게 다룹니다. */
    Optional<ExchangeQuote> findByIdAndUserId(UUID quoteId, UUID userId);
}
