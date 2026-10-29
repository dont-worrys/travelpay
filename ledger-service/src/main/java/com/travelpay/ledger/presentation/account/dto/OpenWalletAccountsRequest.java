package com.travelpay.ledger.presentation.account.dto;

import com.travelpay.ledger.application.account.dto.OpenWalletAccountsCommand;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record OpenWalletAccountsRequest(@NotNull(message = "지갑 ID를 입력해 주세요.") UUID walletId) {

    public OpenWalletAccountsCommand toCommand() {
        return new OpenWalletAccountsCommand(walletId);
    }
}
