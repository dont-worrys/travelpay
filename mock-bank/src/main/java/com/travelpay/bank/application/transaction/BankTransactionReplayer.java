package com.travelpay.bank.application.transaction;

import com.travelpay.bank.application.transaction.dto.BankTransactionResult;
import com.travelpay.bank.domain.transaction.BankTransactionRepository;
import com.travelpay.bank.domain.transaction.BankTransactionType;
import com.travelpay.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 같은 requestId의 기존 기록을 찾아 그대로 돌려준다. 유형이 다르면 409 */
@Component
@RequiredArgsConstructor
public class BankTransactionReplayer {

    private final BankTransactionRepository bankTransactionRepository;

    public Optional<BankTransactionResult> find(String requestId, BankTransactionType expectedType) {
        return bankTransactionRepository.findByRequestId(requestId)
                .map(transaction -> {
                    if (transaction.getType() != expectedType) {
                        throw new BusinessException(BankErrorCode.REQUEST_ID_CONFLICT);
                    }
                    return BankTransactionResult.replayed(transaction);
                });
    }

    /** 유니크 위반 직후 호출: 먼저 커밋된 기록이 반드시 있다. */
    public BankTransactionResult replay(String requestId, BankTransactionType expectedType) {
        return find(requestId, expectedType)
                .orElseThrow(() -> new IllegalStateException("중복 requestId 위반 후 기존 거래를 찾을 수 없습니다."));
    }
}
