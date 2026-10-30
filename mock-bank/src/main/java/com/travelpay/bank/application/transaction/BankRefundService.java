package com.travelpay.bank.application.transaction;

import com.travelpay.bank.application.transaction.dto.BankRefundCommand;
import com.travelpay.bank.application.transaction.dto.BankTransactionResult;
import com.travelpay.bank.domain.transaction.BankDuplicateRefundException;
import com.travelpay.bank.domain.transaction.BankDuplicateRequestException;
import com.travelpay.bank.domain.transaction.BankTransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 트랜잭션 밖 래퍼.
 * 동시 환불로 refund_once를 어기면 환불 트랜잭션이 롤백된 뒤 새 트랜잭션에서 ALREADY_REFUNDED를 기록한다.
 * 같은 requestId가 동시에 저장되면 먼저 커밋된 기록을 다시 읽는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankRefundService {

    private final BankRefundTransaction refundTransaction;
    private final BankTransactionRecorder recorder;
    private final BankTransactionReplayer replayer;

    public BankTransactionResult refund(BankRefundCommand command) {
        BankTransactionResult result;
        try {
            result = refundTransaction.refund(command);
        } catch (BankDuplicateRefundException e) {
            result = recordAlreadyRefunded(command);
        } catch (BankDuplicateRequestException e) {
            result = replayer.replay(command.requestId(), BankTransactionType.REFUND);
        }
        log.info("환불 처리 requestId={}, originalTxId={}, status={}, failureCode={}, created={}",
                result.requestId(), command.originalTxId(), result.status(), result.failureCode(), result.created());
        return result;
    }

    private BankTransactionResult recordAlreadyRefunded(BankRefundCommand command) {
        try {
            return recorder.recordAlreadyRefunded(command);
        } catch (BankDuplicateRequestException e) {
            return replayer.replay(command.requestId(), BankTransactionType.REFUND);
        }
    }
}
