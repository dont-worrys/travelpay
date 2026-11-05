package com.travelpay.payment.transfer.client;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi;
import com.travelpay.payment.transfer.client.LedgerTransactionResponse.RejectReason;
import com.travelpay.payment.transfer.client.LedgerTransactionResponse.Status;
import feign.FeignException;
import org.springframework.stereotype.Component;

/**
 * 송금의 원장 거래 결과를 reference(TRANSFER + 송금 ID)로 조회합니다({@link LedgerFeignApi#getTransaction}).
 * 운영자 확정(MANUAL_REVIEW 보정)에서 원장 반영 여부를 서버가 직접 확인하는 데 씁니다.
 * 404 + LEDGER_TRANSACTION_NOT_FOUND만 거래 없음(empty)으로 보고, 그 밖의 실패는 common 규칙({@link FeignErrors#toCommonException})을,
 * 해석할 수 없는 성공 응답은 502를 따릅니다.
 */
@Component
public class TransferLedgerReader {

    private static final int NOT_FOUND = 404;
    private static final String TRANSACTION_NOT_FOUND = "LEDGER_TRANSACTION_NOT_FOUND";

    private final LedgerFeignApi ledgerFeignApi;

    public TransferLedgerReader(LedgerFeignApi ledgerFeignApi) {
        this.ledgerFeignApi = ledgerFeignApi;
    }

    public Optional<LedgerTransactionResponse> findTransfer(UUID transferId) {
        ApiResponse<LedgerFeignApi.TransactionResultResponse> response;
        try {
            response = ledgerFeignApi.getTransaction(LedgerTransactionRequest.REFERENCE_TYPE, transferId);
        } catch (FeignException exception) {
            if (exception.status() == NOT_FOUND && FeignErrors.hasErrorCode(exception, TRANSACTION_NOT_FOUND)) {
                return Optional.empty();
            }
            throw FeignErrors.toCommonException(exception);
        }
        if (response == null || !response.success() || response.data() == null
                || response.data().transactionId() == null) {
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY);
        }
        LedgerFeignApi.TransactionResultResponse data = response.data();
        if (Status.POSTED.name().equals(data.status())) {
            return Optional.of(LedgerTransactionResponse.posted(data.transactionId()));
        }
        if (Status.REJECTED.name().equals(data.status())) {
            return Optional.of(LedgerTransactionResponse.rejected(data.transactionId(), rejectReason(data.rejectReason())));
        }
        throw new BusinessException(CommonErrorCode.BAD_GATEWAY);
    }

    private static RejectReason rejectReason(String value) {
        try {
            return RejectReason.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY);
        }
    }
}
