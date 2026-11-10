package com.travelpay.payment.application.merchant;

import com.travelpay.common.error.BusinessException;
import com.travelpay.payment.application.merchant.dto.CreateMerchantCommand;
import com.travelpay.payment.application.merchant.dto.MerchantResult;
import com.travelpay.payment.domain.merchant.Merchant;
import com.travelpay.payment.domain.merchant.MerchantRepository;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;


@Component
@RequiredArgsConstructor
public class MerchantTransactionSteps {

    static final int TX_TIMEOUT_SECONDS = 3;

    private final MerchantRepository merchantRepository;

    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public MerchantResult create(CreateMerchantCommand command) {
        Merchant merchant = merchantRepository.save(Merchant.create(command.name(), command.currency(), command.ownerUserId()));
        return MerchantResult.from(merchant);
    }

    @Transactional(readOnly = true, timeout = TX_TIMEOUT_SECONDS)
    public MerchantResult findMerchant(UUID merchantId) {
        return merchantRepository.findById(merchantId)
                .map(MerchantResult::from)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.MERCHANT_NOT_FOUND));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public MerchantResult completeCreate(UUID merchantId) {
        Merchant merchant = merchantRepository.findByIdForUpdate(merchantId)
                .orElseThrow(() -> new IllegalStateException("생성 중인 가맹점을 찾지 못했습니다."));
        if (merchant.isPending()) {
            merchant.activate(merchantId);
        }
        return MerchantResult.from(merchant);
    }
}
