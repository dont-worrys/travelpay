package com.travelpay.exchange.application.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 사람이 알아야 할 주문 적체를 세어 둡니다. 지표(게이지)는 이 값을 읽기만 합니다.
 * Prometheus가 지표를 읽을 때마다 DB를 조회하지 않도록, 복구 스케줄러가 주기마다 refresh()로 미리 세어 둡니다.
 *
 * - 처리 정체: 신청 후 30분이 지났는데 아직 PROCESSING인 주문. 원장 장애를 일찍 알아차리기 위한 것이며 상태는 바꾸지 않는다.
 *   재시도는 복구 스케줄러가 계속하고, 신청 후 24시간이 지나면 MANUAL_REVIEW로 넘어가는 규칙도 그대로다.
 *   30분: 복구 대기가 30초부터 최대 16분까지 늘어나므로, 30분이면 재시도를 여러 번 했는데도 결과를 확정하지 못한 상태다.
 * - 사람 확인 대기: MANUAL_REVIEW 주문. 관리자가 처리해야 줄어든다.
 */
@Slf4j
@Component
public class ExchangeOrderBacklogMonitor {

    static final Duration STUCK_AFTER = Duration.ofMinutes(30);

    /** 정체 로그에 남길 주문 ID 수 (오래된 순) */
    static final int STUCK_SAMPLE_SIZE = 5;

    private final ExchangeOrderRepository orderRepository;
    private final Clock clock;
    private final AtomicLong stuckProcessing = new AtomicLong();
    private final AtomicLong manualReview = new AtomicLong();

    public ExchangeOrderBacklogMonitor(ExchangeOrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    /**
     * 두 값을 다시 셉니다. 예외를 던지지 않습니다.
     * 세기에 실패하면 이전 값을 그대로 두고 경고만 남깁니다. 한쪽이 실패해도 다른 쪽은 셉니다.
     * 0으로 바꾸지 않는 이유: DB 장애로 못 센 것을 "정체·대기 없음"으로 보이게 하면 알림이 꺼진 것처럼 보이기 때문입니다.
     */
    public void refresh() {
        Instant cutoff = clock.instant().minus(STUCK_AFTER);
        try {
            long stuck = orderRepository.countProcessingRequestedAtOrBefore(cutoff);
            stuckProcessing.set(stuck);
            if (stuck > 0) {
                log.error("신청 후 {}분 넘게 확정되지 않은 환전 주문 {}건 (원장 확인 필요), 오래된 순 orderIds={}",
                        STUCK_AFTER.toMinutes(), stuck,
                        orderRepository.findProcessingIdsRequestedAtOrBefore(cutoff, STUCK_SAMPLE_SIZE));
            }
        } catch (RuntimeException e) {
            log.warn("처리 정체 주문 수를 세지 못해 이전 값({})을 유지합니다: {}", stuckProcessing.get(), e.toString());
        }
        try {
            manualReview.set(orderRepository.countManualReview());
        } catch (RuntimeException e) {
            log.warn("사람 확인 대기 주문 수를 세지 못해 이전 값({})을 유지합니다: {}", manualReview.get(), e.toString());
        }
    }

    /** 마지막으로 센 처리 정체 주문 수입니다. 아직 한 번도 세지 않았으면 0입니다. */
    public long stuckProcessingCount() {
        return stuckProcessing.get();
    }

    /** 마지막으로 센 사람 확인 대기 주문 수입니다. 아직 한 번도 세지 않았으면 0입니다. */
    public long manualReviewCount() {
        return manualReview.get();
    }
}
