package com.travelpay.payment.transfer.client;

import java.util.List;
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
 * 결과와 함께 원장에 기록된 거래 항목을 돌려주어, 원장 거래 내용이 송금과 같은지 대조할 수 있게 합니다.
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

    /**
     * 송금 ID를 reference로 원장 거래 결과를 조회합니다.
     *
     * @return 원장 거래가 있으면 POSTED(항목 포함) 또는 REJECTED(거절 사유 포함, 항목 없음), 404 LEDGER_TRANSACTION_NOT_FOUND이면 empty
     * @throws BusinessException 그 밖의 원장 오류는 common 규칙의 5xx·502, 해석할 수 없는 성공 응답
     *         (POSTED인데 항목이 없거나 항목 값이 비어 있는 경우 포함)은 502
     */
    public Optional<LedgerTransferRecord> findTransfer(UUID transferId) {
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
            List<LedgerTransactionRequest.Entry> entries = entries(data.entries());
            if (entries.isEmpty()) {
                throw new BusinessException(CommonErrorCode.BAD_GATEWAY);
            }
            return Optional.of(new LedgerTransferRecord(LedgerTransactionResponse.posted(data.transactionId()), entries));
        }
        if (Status.REJECTED.name().equals(data.status())) {
            return Optional.of(new LedgerTransferRecord(
                    LedgerTransactionResponse.rejected(data.transactionId(), rejectReason(data.rejectReason())),
                    List.of()));
        }
        throw new BusinessException(CommonErrorCode.BAD_GATEWAY);
    }

    /**
     * 원장 항목을 등록 요청과 같은 형식으로 바꿉니다. 비교에 쓰는 값(ownerType·currency·direction·amount·entryType)이 비어 있으면 502입니다.
     * walletId는 SYSTEM 계정이면 null일 수 있어 그대로 둡니다(송금 항목과 다르므로 대조에서 불일치가 됩니다). accountType은 비교하지 않습니다.
     */
    private static List<LedgerTransactionRequest.Entry> entries(List<LedgerFeignApi.EntryResult> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(entry -> {
                    if (entry == null || entry.amount() == null || entry.ownerType() == null || entry.currency() == null
                            || entry.direction() == null || entry.entryType() == null) {
                        throw new BusinessException(CommonErrorCode.BAD_GATEWAY);
                    }
                    return new LedgerTransactionRequest.Entry(entry.ownerType(), entry.walletId(), entry.currency(),
                            entry.direction(), entry.amount(), entry.entryType());
                })
                .toList();
    }

    /** 원장 거절 사유 문자열을 변환합니다. 없거나 모르는 값이면 502입니다. */
    private static RejectReason rejectReason(String value) {
        try {
            return RejectReason.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY);
        }
    }
}
