package com.travelpay.wallet.application.charge;

import com.travelpay.common.error.BusinessException;
import com.travelpay.wallet.application.charge.dto.ChargeAcceptedResult;
import com.travelpay.wallet.application.charge.dto.ChargeResult;
import com.travelpay.wallet.application.charge.dto.RequestChargeCommand;
import com.travelpay.wallet.application.wallet.WalletTransactionSteps;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.WalletErrorCode;
import com.travelpay.wallet.domain.charge.AccountNumberCipher;
import com.travelpay.wallet.domain.charge.AccountNumberCipherException;
import com.travelpay.wallet.domain.charge.BankAccount;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import com.travelpay.wallet.domain.charge.DuplicateChargeRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChargeService {

    /** 충전 원천(CHARGE_SOURCE) 계정·충전 분개가 원화만 있다 */
    private static final Currency CHARGE_CURRENCY = Currency.KRW;

    private final ChargeTransactionSteps steps;
    private final WalletTransactionSteps walletSteps;
    private final UserFundingIdentityClient userFundingIdentityClient;
    private final AccountNumberCipher cipher;

    /**
     * 충전 접수. 회원 실명 조회는 트랜잭션 밖에서 하고, 실패하면 아무것도 저장하지 않는다.
     * 같은 멱등 키의 기존 충전은 지갑 확인·외부 호출보다 먼저 찾아, 내용이 같으면 그 결과를, 다르면 409를 돌려준다.
     */
    public ChargeAcceptedResult requestCharge(RequestChargeCommand command) {
        UUID idempotencyKey = parseIdempotencyKey(command.idempotencyKey());
        if (command.currency() != CHARGE_CURRENCY) {
            throw new BusinessException(WalletErrorCode.CHARGE_CURRENCY_NOT_SUPPORTED);
        }
        UUID userId = command.userId();

        Optional<ChargeRequest> existing = steps.findByIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }

        UUID walletId = walletSteps.findActiveWalletId(userId);
        String realName = userFundingIdentityClient.findRealName(userId);
        BankAccount bankAccount = BankAccount.encrypt(command.bankCode(), command.accountNumber(), cipher);
        ChargeRequest chargeRequest = ChargeRequest.create(userId, walletId, command.amount(), command.currency(),
                bankAccount, realName, idempotencyKey);
        try {
            ChargeRequest saved = steps.accept(chargeRequest);
            return new ChargeAcceptedResult(saved.getId(), saved.getStatus());
        } catch (DuplicateChargeRequestException e) {
            // 같은 키의 동시 요청이 먼저 커밋됨. 새 트랜잭션에서 다시 읽어 같은 규칙으로 응답
            ChargeRequest winner = steps.findByIdempotencyKey(userId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("먼저 저장된 충전을 다시 조회하지 못했습니다."));
            return replay(winner, command);
        }
    }

    /** 본인 충전만 조회한다. 타인 충전과 없는 충전은 같은 404로 응답한다 */
    public ChargeResult getCharge(UUID userId, UUID chargeId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(chargeId, "chargeId");
        ChargeRequest charge = steps.findById(chargeId)
                .filter(found -> found.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(WalletErrorCode.CHARGE_NOT_FOUND));
        String accountNumberMask = decrypting(charge.getId(),
                () -> charge.getBankAccount().maskedAccountNumber(cipher));
        return new ChargeResult(charge.getId(), charge.getStatus(), charge.getAmount(), charge.getCurrency(),
                charge.getBankAccount().getBankCode(), accountNumberMask,
                charge.getFailureCode(),
                charge.getFailureCode() != null ? charge.getFailureCode().displayMessage() : null,
                charge.getCreatedAt(), charge.getUpdatedAt());
    }

    private ChargeAcceptedResult replay(ChargeRequest existing, RequestChargeCommand command) {
        boolean same = decrypting(existing.getId(), () -> existing.isSameRequest(command.amount(),
                command.currency(), command.bankCode(), command.accountNumber(), cipher));
        if (!same) {
            throw new BusinessException(WalletErrorCode.CHARGE_IDEMPOTENCY_CONFLICT);
        }
        return new ChargeAcceptedResult(existing.getId(), existing.getStatus());
    }

    /** 복호화 실패는 chargeId와 예외 클래스명만 남긴다 (암호화 구현체는 로그를 남기지 않음) */
    private <T> T decrypting(UUID chargeId, Supplier<T> call) {
        try {
            return call.get();
        } catch (AccountNumberCipherException e) {
            log.error("계좌번호 복호화 실패 chargeId={} exception={}", chargeId,
                    e.getCause() != null ? e.getCause().getClass().getName() : e.getClass().getName());
            throw e;
        }
    }

    /** 표준 UUID 표기만 받는다. 형식 오류는 DB 조회 전에 400 */
    private static UUID parseIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(WalletErrorCode.CHARGE_INVALID_IDEMPOTENCY_KEY);
        }
        String normalized = value.strip();
        UUID parsed;
        try {
            parsed = UUID.fromString(normalized);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(WalletErrorCode.CHARGE_INVALID_IDEMPOTENCY_KEY);
        }
        // UUID.fromString이 허용하는 축약형까지 받아들이지 않도록 표준 표기와 비교
        if (!parsed.toString().equalsIgnoreCase(normalized)) {
            throw new BusinessException(WalletErrorCode.CHARGE_INVALID_IDEMPOTENCY_KEY);
        }
        return parsed;
    }
}
