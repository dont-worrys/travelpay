package com.travelpay.bank.application.transaction;

import com.travelpay.bank.application.transaction.dto.BankTransactionResult;
import com.travelpay.bank.domain.transaction.BankTransactionRepository;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.util.PageableUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/** 거래를 만들거나 바꾸지 않는 읽기 전용 조회 (MANUAL_REVIEW 조사·충전 대사용) */
@Service
@RequiredArgsConstructor
public class BankTransactionQueryService {

    static final int DEFAULT_PAGE = 0;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    private static final Sort LIST_SORT = Sort.by(Sort.Direction.ASC, "createdAt");
    private static final Set<String> LIST_SORT_FIELDS = Set.of("createdAt");

    private final BankTransactionRepository bankTransactionRepository;

    @Transactional(readOnly = true)
    public BankTransactionResult findByRequestId(String requestId) {
        return bankTransactionRepository.findByRequestId(requestId)
                .map(BankTransactionResult::replayed)
                .orElseThrow(() -> new BusinessException(BankErrorCode.TRANSACTION_NOT_FOUND));
    }

    /**
     * created_at이 [from, to)인 거래. 정렬은 created_at 오름차순 고정, 같은 시각은 id 오름차순.
     * page·size가 null이면 기본값, size는 최대 100으로 줄인다. offset이 int 범위를 넘으면 400.
     * 마이크로초보다 세밀한 from·to는 다음 마이크로초로 올려 조회한다.
     */
    @Transactional(readOnly = true)
    public Page<BankTransactionResult> findPage(Instant from, Instant to, Integer page, Integer size) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        int pageNumber = page == null ? DEFAULT_PAGE : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (from.isAfter(to) || pageNumber < 0 || pageSize < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        Pageable pageable = PageableUtils.toStablePageable(
                PageRequest.of(pageNumber, pageSize, LIST_SORT), LIST_SORT_FIELDS, "id", MAX_PAGE_SIZE);
        // 보정된 size 기준 offset이 int를 넘으면 JPA firstResult(int)에서 넘친다
        if (pageable.getOffset() > Integer.MAX_VALUE) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return bankTransactionRepository.findCreatedBetween(ceilToMicros(from), ceilToMicros(to), pageable)
                .map(BankTransactionResult::replayed);
    }

    /**
     * DB(timestamptz)는 마이크로초 단위라, 저장 시각 t에 대해 t >= x ⇔ t >= ceil(x) 이고 t < x ⇔ t < ceil(x) 이다.
     * 올려서 비교해도 [from, to) 계약은 그대로다.
     */
    private static Instant ceilToMicros(Instant instant) {
        Instant truncated = instant.truncatedTo(ChronoUnit.MICROS);
        if (truncated.equals(instant)) {
            return instant;
        }
        try {
            return truncated.plus(1, ChronoUnit.MICROS);
        } catch (DateTimeException e) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
    }
}
