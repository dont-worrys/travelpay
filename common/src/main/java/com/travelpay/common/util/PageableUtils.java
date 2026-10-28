package com.travelpay.common.util;

import java.util.Objects;
import java.util.Set;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class PageableUtils {

    private PageableUtils() {
    }

    /**
     * 페이지 크기를 제한하고, 요청 정렬을 검증한 뒤 고유 필드를 마지막 정렬 기준으로 추가합니다.
     * uniqueSortField는 호출자가 실제 조회 대상의 NULL 없는 고유 속성으로 지정해야 합니다.
     * sortableFields는 클라이언트에게 허용한 속성이므로 uniqueSortField를 반드시 포함할 필요는 없습니다.
     * 대소문자 무시 정렬에는 원래 고유값의 정렬을 추가해 동률을 구분합니다.
     * 동률의 순서를 결정하며, 페이지 조회 사이의 데이터 추가·삭제까지 방지하지는 않습니다.
     */
    public static Pageable toStablePageable(
            Pageable pageable,
            Set<String> sortableFields,
            String uniqueSortField,
            int maxPageSize
    ) {
        Objects.requireNonNull(pageable, "pageable");
        Objects.requireNonNull(sortableFields, "sortableFields");
        Objects.requireNonNull(uniqueSortField, "uniqueSortField");

        if (uniqueSortField.isBlank()) {
            throw new IllegalArgumentException("uniqueSortField must not be blank");
        }
        if (maxPageSize < 1) {
            throw new IllegalArgumentException("maxPageSize must be greater than zero");
        }
        if (pageable.isUnpaged()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }

        Sort sort = pageable.getSort();
        for (Sort.Order order : sort) {
            if (!sortableFields.contains(order.getProperty())) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
            }
        }

        boolean hasRawUniqueOrder = sort.stream()
                .anyMatch(order -> uniqueSortField.equals(order.getProperty()) && !order.isIgnoreCase());
        if (!hasRawUniqueOrder) {
            sort = sort.and(Sort.by(Sort.Direction.ASC, uniqueSortField));
        }

        return PageRequest.of(
                pageable.getPageNumber(),
                Math.min(pageable.getPageSize(), maxPageSize),
                sort
        );
    }
}
