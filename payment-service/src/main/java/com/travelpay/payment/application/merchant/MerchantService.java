package com.travelpay.payment.application.merchant;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.payment.application.merchant.client.MerchantLedgerClient;
import com.travelpay.payment.application.merchant.client.MerchantOwnerClient;
import com.travelpay.payment.application.merchant.dto.CreateMerchantCommand;
import com.travelpay.payment.application.merchant.dto.MerchantBalanceResult;
import com.travelpay.payment.application.merchant.dto.MerchantResult;
import com.travelpay.payment.domain.merchant.MerchantStatus;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantService {

    private final MerchantTransactionSteps steps;
    private final MerchantLedgerClient ledgerClient;
    private final MerchantOwnerClient ownerClient;

    public MerchantResult createMerchant(CreateMerchantCommand command) {
        if (!ownerClient.isActiveUser(command.ownerUserId())) {
            throw new BusinessException(PaymentErrorCode.MERCHANT_OWNER_NOT_FOUND);
        }
        MerchantResult created = steps.create(command);
        try {
            ledgerClient.openAccounts(created.merchantId(), created.currency());
        } catch (BusinessException e) {
            log.warn("가맹점 원장 계정 생성 실패, PENDING 유지 merchantId={} code={}",
                    created.merchantId(), e.getErrorCode().getCode());
            return created;
        }
        return steps.completeCreate(created.merchantId());
    }

    public MerchantResult retryLedgerAccount(UUID merchantId) {
        MerchantResult merchant = steps.findMerchant(merchantId);
        if (merchant.status() != MerchantStatus.PENDING) {
            return merchant;
        }
        ledgerClient.openAccounts(merchant.merchantId(), merchant.currency());
        return steps.completeCreate(merchant.merchantId());
    }

    public MerchantBalanceResult getBalance(AuthenticatedUser user, UUID merchantId) {
        MerchantResult merchant = steps.findMerchant(merchantId);
        if (user.role() != AuthRole.MASTER && !merchant.ownerUserId().equals(user.userId())) {
            throw new BusinessException(PaymentErrorCode.MERCHANT_NOT_FOUND);
        }
        if (merchant.status() == MerchantStatus.PENDING || merchant.ledgerWalletId() == null) {
            throw new BusinessException(PaymentErrorCode.MERCHANT_NOT_ACTIVE);
        }
        long balance = ledgerClient.getBalance(merchant.ledgerWalletId(), merchant.currency());
        return new MerchantBalanceResult(merchant.merchantId(), merchant.currency(), balance);
    }
}
