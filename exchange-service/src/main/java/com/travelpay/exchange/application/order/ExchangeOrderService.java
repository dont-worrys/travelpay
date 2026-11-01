package com.travelpay.exchange.application.order;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.domain.order.DuplicateExchangeOrderException;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderErrorCode;
import com.travelpay.exchange.domain.order.ExchangeOrderRepository;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistoryRepository;
import com.travelpay.exchange.domain.order.IdempotencyKey;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.ExchangeQuoteErrorCode;
import com.travelpay.exchange.domain.quote.ExchangeQuoteRepository;
import com.travelpay.exchange.domain.wallet.WalletBalanceFetchException;
import com.travelpay.exchange.domain.wallet.WalletBalanceReader;
import com.travelpay.exchange.domain.wallet.WalletBalances;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 환전·재환전 신청을 받아 견적 스냅샷 주문을 PROCESSING으로 저장한 뒤 원장에 보내 결과를 반영합니다.
 *
 * 메서드 전체를 트랜잭션으로 묶지 않습니다. 지갑 조회·원장 호출(원격 호출) 동안 DB 커넥션을 잡지 않고,
 * 주문 INSERT만 짧은 트랜잭션으로 실행합니다. INSERT가 UNIQUE에 걸리면 그 트랜잭션은 롤백되고
 * (PostgreSQL은 제약 위반 뒤 같은 트랜잭션에서 조회할 수 없음) 새 트랜잭션에서 다시 조회해 원인을 가립니다.
 * 주문이 커밋된 뒤의 원장 처리는 실패해도 신청을 실패시키지 않습니다. 주문은 PROCESSING으로 남고 복구 스케줄러가 이어서 처리합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeOrderService {

    private final ExchangeOrderRepository orderRepository;
    private final ExchangeOrderStatusHistoryRepository historyRepository;
    private final ExchangeQuoteRepository quoteRepository;
    private final WalletBalanceReader walletBalanceReader;
    private final ExchangeOrderLedgerProcessor ledgerProcessor;
    private final TransactionOperations transactionOperations;
    private final Clock clock;

    /**
     * 환전을 신청하고 원장 처리까지 마친 주문을 돌려줍니다. 결과를 모르면 PROCESSING인 주문입니다.
     * 같은 멱등 키 재요청은 견적·지갑 확인보다 먼저 찾아, 같은 신청이면 기존 주문을 돌려줍니다.
     * 견적이 나중에 사라지거나 만료돼도 이미 만든 주문은 재요청에 그대로 응답하기 위해서입니다.
     * 기존 주문이 아직 PROCESSING이면 원장에 다시 보내 결과를 반영합니다.
     *
     * @throws BusinessException 멱등 키 형식 오류(400), 견적 없음(404), 견적 만료·이미 사용·멱등 키 재사용(409),
     *                           지갑 없음(404), 지갑 사용 불가(409), 지갑 확인 실패(503)
     */
    public ExchangeOrder place(PlaceOrderCommand command) {
        IdempotencyKey key = new IdempotencyKey(command.idempotencyKey());
        Optional<ExchangeOrder> existing = orderRepository.findByUserIdAndIdempotencyKey(command.userId(), key);
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }

        ExchangeQuote quote = quoteRepository.findByIdAndUserId(command.quoteId(), command.userId())
                .orElseThrow(() -> new BusinessException(ExchangeQuoteErrorCode.QUOTE_NOT_FOUND));
        // 만료는 지갑 조회 전에 판단한다. 원격 호출이 느려도 신청 시각은 요청을 받은 시각이다.
        Instant requestedAt = clock.instant();
        ExchangeOrder order = ExchangeOrder.place(quote, command.walletId(), key, requestedAt);
        checkWallet(command.userId(), order);

        try {
            ExchangeOrder saved = transactionOperations.execute(status -> {
                ExchangeOrder inserted = orderRepository.save(order);
                historyRepository.save(inserted.placedHistory());
                return inserted;
            });
            return processLedger(saved);
        } catch (DuplicateExchangeOrderException e) {
            return resolveDuplicate(command, key);
        }
    }

    /** 본인 주문을 조회합니다. 없거나 다른 사람의 주문이면 EXCHANGE_ORDER_NOT_FOUND 오류입니다. */
    @Transactional(readOnly = true)
    public ExchangeOrder find(UUID userId, UUID orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException(ExchangeOrderErrorCode.ORDER_NOT_FOUND));
    }

    /**
     * 신청 지갑이 본인 지갑이고, 보내는 통화로 돈을 빼고 받는 통화로 돈을 받을 수 있는지 확인합니다.
     * 잔액은 보지 않습니다. 잔액 판단은 원장이 거래를 기록할 때 합니다.
     * 다른 사람의 지갑 ID는 지갑 존재를 알리지 않도록 지갑 없음과 같게 다룹니다.
     */
    private void checkWallet(UUID userId, ExchangeOrder order) {
        WalletBalances wallet;
        try {
            wallet = walletBalanceReader.read(userId)
                    .orElseThrow(() -> new BusinessException(ExchangeOrderErrorCode.WALLET_NOT_FOUND));
        } catch (WalletBalanceFetchException e) {
            log.warn("지갑을 확인하지 못해 환전 신청을 받지 않습니다: userId={}, reason={}", userId, e.getMessage());
            throw new BusinessException(ExchangeOrderErrorCode.WALLET_SERVICE_UNAVAILABLE, e);
        }
        if (!wallet.walletId().equals(order.getWalletId())) {
            throw new BusinessException(ExchangeOrderErrorCode.WALLET_NOT_FOUND);
        }
        if (!wallet.canSpend(order.getFromCurrency()) || !wallet.canReceive(order.getToCurrency())) {
            throw new BusinessException(ExchangeOrderErrorCode.WALLET_NOT_AVAILABLE);
        }
    }

    /**
     * 같은 멱등 키로 이미 만든 주문입니다. 처음과 같은 견적·지갑이면 그 주문을, 다르면 409입니다.
     * 아직 PROCESSING이면 원장에 다시 보냅니다. 확정된 주문은 원장을 부르지 않고 그대로 돌려줍니다.
     */
    private ExchangeOrder replay(ExchangeOrder existing, PlaceOrderCommand command) {
        if (!existing.isSameRequest(command.quoteId(), command.walletId())) {
            throw new BusinessException(ExchangeOrderErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return processLedger(existing);
    }

    /**
     * 주문은 이미 커밋됐으므로 원장 처리 중 예상 밖 오류(DB 일시 장애 등)가 나도 신청은 실패시키지 않습니다.
     * 그때는 받은 주문(PROCESSING)을 돌려주고, 복구 스케줄러가 같은 요청을 다시 보냅니다.
     */
    private ExchangeOrder processLedger(ExchangeOrder order) {
        try {
            return ledgerProcessor.process(order);
        } catch (RuntimeException e) {
            log.error("원장 처리 중 오류로 처리 중인 주문을 응답합니다: orderId={}, exception={}",
                    order.getId(), e.getClass().getName(), e);
            return order;
        }
    }

    /**
     * INSERT가 주문 UNIQUE에 걸린 경우입니다. 같은 멱등 키의 동시 요청이 먼저 저장했으면 재요청과 같이 응답하고,
     * 그 키의 주문이 없으면 다른 신청이 이 견적을 먼저 사용한 것입니다.
     */
    private ExchangeOrder resolveDuplicate(PlaceOrderCommand command, IdempotencyKey key) {
        return orderRepository.findByUserIdAndIdempotencyKey(command.userId(), key)
                .map(winner -> replay(winner, command))
                .orElseThrow(() -> new BusinessException(ExchangeOrderErrorCode.QUOTE_ALREADY_USED));
    }
}
