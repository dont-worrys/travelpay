package com.travelpay.ledger.presentation.transaction.dto;

import com.travelpay.ledger.application.transaction.dto.RegisterTransactionCommand;
import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.account.AccountType;
import com.travelpay.ledger.domain.account.OwnerType;
import com.travelpay.ledger.domain.transaction.Direction;
import com.travelpay.ledger.domain.transaction.ReferenceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 계정 지정 방식·통화별 차변=대변 같은 교차 규칙은 서비스에서 확인한다 */
public record RegisterTransactionRequest(
        @NotNull(message = "참조 유형을 입력해 주세요.") ReferenceType referenceType,
        @NotNull(message = "참조 ID를 입력해 주세요.") UUID referenceId,
        UUID userId,
        @Size(max = 255, message = "설명은 255자 이하로 입력해 주세요.") String description,
        UUID originalTransactionId,
        @NotNull(message = "거래 항목을 입력해 주세요.")
        @Size(min = 2, message = "거래 항목은 2개 이상이어야 합니다.")
        List<@NotNull(message = "거래 항목을 입력해 주세요.") @Valid EntryRequest> entries) {

    public RegisterTransactionCommand toCommand() {
        return new RegisterTransactionCommand(referenceType, referenceId, userId, description,
                originalTransactionId, entries.stream().map(EntryRequest::toCommand).toList());
    }

    public record EntryRequest(
            @NotNull(message = "소유자 유형을 입력해 주세요.") OwnerType ownerType,
            UUID walletId,
            AccountType accountType,
            @NotNull(message = "통화를 입력해 주세요.") Currency currency,
            @NotNull(message = "방향을 입력해 주세요.") Direction direction,
            @NotNull(message = "금액을 입력해 주세요.")
            @Positive(message = "금액은 0보다 커야 합니다.") Long amount,
            @NotBlank(message = "항목 유형을 입력해 주세요.")
            @Size(max = 30, message = "항목 유형은 30자 이하로 입력해 주세요.") String entryType) {

        RegisterTransactionCommand.Entry toCommand() {
            return new RegisterTransactionCommand.Entry(ownerType, walletId, accountType, currency,
                    direction, amount, entryType);
        }
    }
}
