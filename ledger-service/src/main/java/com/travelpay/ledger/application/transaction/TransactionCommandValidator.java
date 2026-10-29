package com.travelpay.ledger.application.transaction;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.ledger.application.transaction.dto.RegisterTransactionCommand;
import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.transaction.Direction;

import java.util.EnumMap;
import java.util.Map;

/**
 * 형식 검증(400). DB에 닿기 전에 확인하며, 위반이면 아무것도 기록하지 않는다.
 * 컨트롤러의 Bean Validation과 일부 겹치지만, 다른 경로로 들어온 명령도 막기 위해 서비스에서 다시 확인한다.
 */
final class TransactionCommandValidator {

    private static final int MIN_ENTRIES = 2;
    private static final int MAX_ENTRIES = 20;

    private TransactionCommandValidator() {
    }

    static void validate(RegisterTransactionCommand command) {
        if (command.referenceType() == null || command.referenceId() == null
                || command.entries().size() < MIN_ENTRIES || command.entries().size() > MAX_ENTRIES) {
            throw invalid();
        }
        Map<Currency, Long> debits = new EnumMap<>(Currency.class);
        Map<Currency, Long> credits = new EnumMap<>(Currency.class);
        for (RegisterTransactionCommand.Entry entry : command.entries()) {
            validateEntry(entry);
            Map<Currency, Long> sums = entry.direction() == Direction.DEBIT ? debits : credits;
            sums.merge(entry.currency(), entry.amount(), TransactionCommandValidator::addExact);
        }
        if (!debits.equals(credits)) {
            throw invalid();
        }
    }

    private static void validateEntry(RegisterTransactionCommand.Entry entry) {
        if (entry == null || entry.ownerType() == null || entry.currency() == null
                || entry.direction() == null || entry.amount() <= 0
                || entry.entryType() == null || entry.entryType().isBlank()) {
            throw invalid();
        }
        boolean validAccount = switch (entry.ownerType()) {
            case USER -> entry.walletId() != null && entry.accountType() == null;
            case SYSTEM -> entry.walletId() == null && entry.accountType() != null;
            // 가맹점 계정 지정 방식은 MVP 이후 결정
            case MERCHANT -> false;
        };
        if (!validAccount) {
            throw invalid();
        }
    }

    private static long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST);
    }
}
