package com.travelpay.exchange.application.order;

import com.travelpay.exchange.domain.order.ExchangeOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 환전 주문 목록 조회 포트입니다. 구현은 infrastructure 계층이 담당합니다.
 * 페이지 조회는 Spring Data의 Page·Pageable을 쓰는데, 도메인은 Spring에 의존하지 않는다는 규칙(ArchitectureTest)이 있어
 * 도메인 저장소가 아니라 애플리케이션 계층의 조회 전용 포트로 둡니다. 주문 상태를 바꾸는 일은 하지 않습니다.
 */
public interface ExchangeOrderQueryRepository {

    /** 조건에 맞는 주문을 pageable의 페이지·크기·정렬대로 돌려줍니다. 정렬 검증과 크기 제한은 호출하는 쪽이 합니다. */
    Page<ExchangeOrder> findPage(ExchangeOrderListCondition condition, Pageable pageable);
}
