package com.travelpay.payment.transfer.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.PageResponse;
import com.travelpay.common.util.PageableUtils;
import com.travelpay.payment.transfer.client.TransferWalletReader;
import com.travelpay.payment.transfer.client.WalletResponse;
import com.travelpay.payment.transfer.domain.Transfer;
import com.travelpay.payment.transfer.domain.TransferCurrency;
import com.travelpay.payment.transfer.domain.TransferDirection;
import com.travelpay.payment.transfer.domain.TransferErrorCode;
import com.travelpay.payment.transfer.domain.TransferRepository;
import com.travelpay.payment.transfer.domain.TransferStatus;
import com.travelpay.payment.transfer.dto.RecipientResponse;
import com.travelpay.payment.transfer.dto.TransferCreateRequest;
import com.travelpay.payment.transfer.dto.TransferResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 송금 접수·취소와 조회를 처리합니다.
 * 1차 MVP는 원장 연동 전이라 잔액을 변경하지 않고 REQUESTED 상태로 접수만 기록합니다.
 */
@Service
public class TransferService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final Set<String> LIST_SORT_FIELDS = Set.of("createdAt", "id");

    // 공백·제어 문자를 제외한 ASCII 1~100자이며, transfer.idempotency_key 길이와 맞춥니다.
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[\\x21-\\x7E]{1,100}$");

    private final TransferRepository transferRepository;
    private final TransferWalletReader walletReader;

    public TransferService(TransferRepository transferRepository, TransferWalletReader walletReader) {
        this.transferRepository = transferRepository;
        this.walletReader = walletReader;
    }

    /**
     * 송금을 접수합니다. 같은 송금인·키·요청 내용의 재요청은 처음 접수 결과를 반환합니다.
     * 지갑 조회(원격 호출) 동안 DB 트랜잭션을 잡지 않도록 메소드 전체를 트랜잭션으로 묶지 않습니다.
     */
    public TransferResponse request(UUID senderId, String idempotencyKey, TransferCreateRequest request) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new BusinessException(TransferErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
        String memo = normalizeMemo(request.memo());
        String requestHash = TransferRequestHasher.hash(
                request.receiverId(), request.amount(), request.currency(), memo);

        var existing = transferRepository.findBySenderIdAndIdempotencyKey(senderId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, senderId);
        }

        if (senderId.equals(request.receiverId())) {
            throw new BusinessException(TransferErrorCode.SELF_TRANSFER);
        }
        if (!TransferCurrency.isSupported(request.currency())) {
            throw new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY);
        }
        // TODO(user-service): 수취인 회원 상태가 ACTIVE인지 확인해야 합니다(인프라 담당자 답변).
        //  내부 회원 조회 API의 경로·응답 형식·제공 시점이 확정되면 추가하고, 아니면 RECEIVER_UNAVAILABLE로 거절합니다.
        // 회원당 지갑은 1개이고 통화별 계정은 원장이 지갑 ID와 통화로 찾으므로, 지갑 상태만 확인합니다.
        UUID sourceWalletId = walletReader.findActiveWalletId(senderId)
                .orElseThrow(() -> new BusinessException(TransferErrorCode.SENDER_WALLET_UNAVAILABLE));
        UUID targetWalletId = walletReader.findActiveWalletId(request.receiverId())
                .orElseThrow(() -> new BusinessException(TransferErrorCode.RECEIVER_UNAVAILABLE));

        Transfer transfer = Transfer.request(
                senderId,
                request.receiverId(),
                sourceWalletId,
                targetWalletId,
                request.amount(),
                request.currency(),
                memo,
                idempotencyKey,
                requestHash
        );
        try {
            return TransferResponse.of(transferRepository.saveAndFlush(transfer), senderId);
        } catch (DataIntegrityViolationException exception) {
            // 같은 키의 동시 요청이 먼저 저장되면 UNIQUE(sender_id, idempotency_key)에 걸립니다.
            Transfer saved = transferRepository.findBySenderIdAndIdempotencyKey(senderId, idempotencyKey)
                    .orElseThrow(() -> exception);
            return replay(saved, requestHash, senderId);
        }
    }

    /** 송금 당사자가 아니면 송금의 존재를 알리지 않도록 없는 송금과 같은 404를 반환합니다. */
    @Transactional(readOnly = true)
    public TransferResponse get(UUID userId, UUID transferId) {
        return transferRepository.findById(transferId)
                .filter(transfer -> transfer.isParticipant(userId))
                .map(transfer -> TransferResponse.of(transfer, userId))
                .orElseThrow(() -> new BusinessException(TransferErrorCode.NOT_FOUND));
    }

    /**
     * 접수(REQUESTED) 상태의 송금을 취소합니다. 1차 MVP는 자금 이동이 없어 복구할 금액이 없습니다.
     * 송금인만 취소할 수 있고 수취인·제3자에게는 없는 송금과 같은 404를 반환하며, 이미 취소된 송금은 현재 상태를 그대로 반환합니다.
     * 동시 요청은 조건부 UPDATE로 한 번만 상태가 바뀝니다.
     */
    @Transactional
    public TransferResponse cancel(UUID senderId, UUID transferId) {
        transferRepository.cancelRequested(transferId, senderId, Instant.now().truncatedTo(ChronoUnit.MICROS));
        Transfer transfer = transferRepository.findById(transferId)
                .filter(found -> found.getSenderId().equals(senderId))
                .orElseThrow(() -> new BusinessException(TransferErrorCode.NOT_FOUND));
        // 원장 연동 후 처리 중·완료 같은 상태가 추가되면 UPDATE 대상이 아니므로 여기서 거절됩니다.
        if (transfer.getStatus() != TransferStatus.CANCELLED) {
            throw new BusinessException(CommonErrorCode.CONFLICT);
        }
        return TransferResponse.of(transfer, senderId);
    }

    /**
     * 송금 전 수취인을 확인합니다. 지갑이 없으면 404, 지갑이 송금할 수 없는 상태면 transferable=false를 반환합니다.
     * 송금 요청과 같은 기준(ACTIVE 지갑)으로 판단하며, 실제 송금 시점에 다시 검증합니다.
     */
    public RecipientResponse findRecipient(UUID userId, UUID receiverId) {
        if (userId.equals(receiverId)) {
            throw new BusinessException(TransferErrorCode.SELF_TRANSFER);
        }
        // TODO(user-service): 수취인 회원 상태(ACTIVE) 확인은 내부 회원 조회 API가 확정되면 송금 요청과 함께 추가합니다.
        boolean transferable = walletReader.findWallet(receiverId)
                .map(WalletResponse::isActive)
                .orElseThrow(() -> new BusinessException(TransferErrorCode.RECEIVER_NOT_FOUND));
        return new RecipientResponse(receiverId, transferable);
    }

    /**
     * 생성 시각 내림차순으로 본인 관련 송금을 조회합니다. direction이 null이면 보낸·받은 내역을 모두 포함합니다.
     * page는 0부터 시작하고 size는 1 이상이어야 하며, 최대 크기를 넘으면 최대 크기로 조회합니다.
     */
    @Transactional(readOnly = true)
    public PageResponse<TransferResponse> list(UUID userId, TransferDirection direction, int page, int size) {
        if (page < 0 || size < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        // 정렬은 고정이며, 생성 시각이 같은 항목은 ID로 순서를 정합니다.
        Pageable pageable = PageableUtils.toStablePageable(
                PageRequest.of(page, size, LIST_SORT), LIST_SORT_FIELDS, "id", MAX_PAGE_SIZE);
        boolean includeSent = direction != TransferDirection.RECEIVED;
        boolean includeReceived = direction != TransferDirection.SENT;

        Page<Transfer> transfers = transferRepository.findByParticipant(
                userId, includeSent, includeReceived, pageable);
        return PageResponse.from(transfers, transfer -> TransferResponse.of(transfer, userId));
    }

    private TransferResponse replay(Transfer transfer, String requestHash, UUID senderId) {
        if (!transfer.getRequestHash().equals(requestHash)) {
            throw new BusinessException(TransferErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return TransferResponse.of(transfer, senderId);
    }

    private static String normalizeMemo(String memo) {
        if (memo == null) {
            return null;
        }
        String stripped = memo.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
