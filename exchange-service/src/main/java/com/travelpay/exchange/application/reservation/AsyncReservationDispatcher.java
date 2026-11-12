package com.travelpay.exchange.application.reservation;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.travelpay.exchange.application.reservation.ReservationExecution.Outcome;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 조건에 닿은 예약을 체결 전용 스레드 하나에 넘기고 바로 돌아갑니다(#282).
 *
 * 예약 체결은 예약마다 지갑 확인과 원장 호출이 있습니다. 부른 스레드(환율 수집 스레드)에서 바로 체결하면 원장이 느릴 때
 * 체결하는 동안 다음 수집이 멈추고, mock 모드는 환율이 30초 넘게 오래되면 견적이 503이 됩니다(개선 전 측정: 원장 3초에
 * 2분 동안 수집 0번). 원장 서킷 브레이커는 이 경로를 막지 못합니다. 체결은 하나씩 차례로 원장을 불러 10초에 2건 정도라
 * 서킷이 판단하는 최소 호출 수(20건)에 닿지 않기 때문입니다. 그래서 체결을 수집 스레드에서 떼어 냅니다.
 *
 * - 체결 스레드는 하나입니다. 예전처럼 하나씩 차례로 체결해, 원장·지갑에 한꺼번에 여러 건을 보내지 않습니다.
 * - 체결 스레드가 앞 목록을 아직 처리 중이면 이번 목록은 넘기지 않고 버립니다. 체결하지 못한 예약은 대기(WAITING)로
 *   남아 다음 새 환율 때 다시 매칭되므로 잃어버리지 않습니다. 대기열에 쌓으면 같은 예약이 수집마다 중복으로 쌓입니다.
 *   같은 예약이 겹쳐 체결되려 해도 체결의 행 잠금(SKIP LOCKED)과 상태 재확인이 한 번만 체결합니다.
 * - 예약 하나가 예상 밖 오류로 실패해도 나머지는 계속 체결합니다. 실패한 예약은 대기로 남습니다.
 * - 서버가 꺼질 때 체결이 끝나기를 기다리지 않습니다. 트랜잭션 중이면 롤백되어 대기로 남고, 원장 호출 중이면 주문이
 *   PROCESSING으로 남아 복구 스케줄러가 확정합니다.
 */
@Slf4j
@Component
public class AsyncReservationDispatcher implements ReservationDispatcher {

    static final String THREAD_NAME = "reservation-execution";

    private final ExchangeReservationExecutor executor;
    private final ExecutorService worker;

    @Autowired
    public AsyncReservationDispatcher(ExchangeReservationExecutor executor) {
        this(executor, singleWorker());
    }

    AsyncReservationDispatcher(ExchangeReservationExecutor executor, ExecutorService worker) {
        this.executor = executor;
        this.worker = worker;
    }

    @Override
    public void dispatch(List<ReservationMatch> matches) {
        int count = matches.stream().mapToInt(match -> match.reservationIds().size()).sum();
        try {
            worker.execute(() -> executeAll(matches));
        } catch (RejectedExecutionException e) {
            log.info("앞 예약 체결이 아직 진행 중이라 이번 매칭 {}건은 넘기지 않습니다. 체결되지 않은 예약은 다음 새 환율 때 다시 매칭됩니다.",
                    count);
        }
    }

    private void executeAll(List<ReservationMatch> matches) {
        Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
        int failed = 0;
        for (ReservationMatch match : matches) {
            for (UUID reservationId : match.reservationIds()) {
                try {
                    outcomes.merge(executor.execute(reservationId, match.rate()).outcome(), 1, Integer::sum);
                } catch (RuntimeException e) {
                    failed++;
                    log.error("예약 체결 중 오류로 대기로 남깁니다: reservationId={}", reservationId, e);
                }
            }
        }
        log.info("예약 체결 결과: outcomes={}, failed={}", outcomes, failed);
    }

    /** 체결 중이던 스레드를 깨워 멈춥니다. 남은 예약은 대기로 남습니다 */
    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    /** 스레드 하나, 대기열 없음: 바쁘면 넘기는 쪽이 RejectedExecutionException을 받는다. 서버 종료를 막지 않게 데몬 스레드 */
    private static ExecutorService singleWorker() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
    }
}
