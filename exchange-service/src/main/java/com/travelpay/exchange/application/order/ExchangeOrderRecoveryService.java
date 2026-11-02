package com.travelpay.exchange.application.order;

import java.time.Clock;
import java.util.List;

import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결과를 모른 채 남은 PROCESSING 주문을 원장에 다시 보냅니다. 원장은 같은 reference를 한 번만 기록하므로 다시 POST만 합니다.
 * 대상은 대기 시간(30초 × 2^확인 횟수, 최대 16분)이 지난 주문이고, 한 건씩 처리기에 넘깁니다.
 * 여러 서버가 같은 주문을 집어도 처리기의 선점으로 원장 호출은 한 번입니다.
 * 트랜잭션을 걸지 않습니다. 원장 호출 동안 DB 커넥션이나 행 잠금을 잡지 않기 위해서입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeOrderRecoveryService {

    /** 원장이 이만큼 연달아 결과를 주지 못하면 장애로 보고 남은 주문은 다음 주기로 미룬다 */
    static final int MAX_CONSECUTIVE_UNSETTLED = 3;

    private final ExchangeOrderRepository orderRepository;
    private final ExchangeOrderLedgerProcessor ledgerProcessor;
    private final Clock clock;

    /** 대상 주문을 처리하고 결과 건수를 돌려줍니다. 한 주문의 예상 밖 오류는 다른 주문 처리를 막지 않습니다. */
    public RecoveryResult recover(int limit) {
        List<ExchangeOrder> targets = orderRepository.findRecoveryTargets(clock.instant(), limit);
        int settled = 0;
        int unsettled = 0;
        int errors = 0;
        int consecutiveUnsettled = 0;
        for (ExchangeOrder target : targets) {
            if (consecutiveUnsettled >= MAX_CONSECUTIVE_UNSETTLED) {
                log.warn("원장 결과를 연달아 확인하지 못해 남은 주문은 다음 주기로 미룹니다: remaining={}",
                        targets.size() - settled - unsettled - errors);
                break;
            }
            try {
                ExchangeOrder processed = ledgerProcessor.process(target);
                if (processed.getStatus() == ExchangeOrderStatus.PROCESSING) {
                    unsettled++;
                    consecutiveUnsettled++;
                } else {
                    settled++;
                    consecutiveUnsettled = 0;
                }
            } catch (RuntimeException e) {
                errors++;
                consecutiveUnsettled++;
                log.error("주문 복구 중 오류: orderId={}, exception={}", target.getId(), e.getClass().getName(), e);
            }
        }
        RecoveryResult result = new RecoveryResult(targets.size(), settled, unsettled, errors);
        if (result.found() > 0) {
            log.info("환전 주문 복구: found={}, settled={}, unsettled={}, errors={}",
                    result.found(), result.settled(), result.unsettled(), result.errors());
        }
        return result;
    }

    /**
     * 복구 한 주기의 결과입니다.
     *
     * @param settled   COMPLETED·FAILED·MANUAL_REVIEW로 바뀐 주문 수
     * @param unsettled 원장 결과를 모르거나 다른 흐름이 처리 중이라 PROCESSING으로 남은 주문 수
     */
    public record RecoveryResult(int found, int settled, int unsettled, int errors) {
    }
}
