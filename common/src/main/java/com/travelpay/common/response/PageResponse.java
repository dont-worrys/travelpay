package com.travelpay.common.response;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** 페이지 목록의 공통 API 응답이며, page는 0부터 시작합니다. */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public PageResponse {
        items = List.copyOf(items);
    }

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }

    /** 응용 Result 등을 API 응답 DTO로 변환하면서 페이지 정보를 유지합니다. */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return from(page.map(mapper));
    }
}
