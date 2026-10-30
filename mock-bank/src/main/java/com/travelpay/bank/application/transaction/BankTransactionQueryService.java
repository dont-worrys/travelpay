package com.travelpay.bank.application.transaction;

import com.travelpay.bank.application.transaction.dto.BankTransactionResult;
import com.travelpay.bank.domain.transaction.BankTransactionRepository;
import com.travelpay.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BankTransactionQueryService {

    private final BankTransactionRepository bankTransactionRepository;

    @Transactional(readOnly = true)
    public BankTransactionResult findByRequestId(String requestId) {
        return bankTransactionRepository.findByRequestId(requestId)
                .map(BankTransactionResult::replayed)
                .orElseThrow(() -> new BusinessException(BankErrorCode.TRANSACTION_NOT_FOUND));
    }
}
