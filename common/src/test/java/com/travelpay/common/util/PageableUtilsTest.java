package com.travelpay.common.util;

import java.util.List;
import java.util.Set;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageableUtilsTest {

    @Test
    void keepsRequestedSortAndAppendsTheConfiguredUniqueField() {
        var requested = PageRequest.of(3, 20, Sort.by(
                Sort.Order.desc("createdAt"), Sort.Order.asc("name")));

        var result = PageableUtils.toStablePageable(
                requested, Set.of("createdAt", "name"), "requestId", 100);

        assertEquals(3, result.getPageNumber());
        assertEquals(20, result.getPageSize());
        assertEquals(List.of(
                Sort.Order.desc("createdAt"),
                Sort.Order.asc("name"),
                Sort.Order.asc("requestId")
        ), result.getSort().toList());
        assertEquals(List.of(
                Sort.Order.desc("createdAt"), Sort.Order.asc("name")
        ), requested.getSort().toList());
    }

    @Test
    void keepsExistingUniqueSortDirectionWithoutAppendingItAgain() {
        var requested = PageRequest.of(2, 15, Sort.by(Sort.Order.desc("id")));

        var result = PageableUtils.toStablePageable(requested, Set.of("id"), "id", 100);

        assertEquals(List.of(Sort.Order.desc("id")), result.getSort().toList());
    }

    @Test
    void caseInsensitiveUniqueSortStillGetsARawUniqueTieBreaker() {
        var requested = PageRequest.of(0, 20, Sort.by(Sort.Order.asc("externalId").ignoreCase()));

        var result = PageableUtils.toStablePageable(
                requested, Set.of("externalId"), "externalId", 100);

        assertEquals(List.of(
                Sort.Order.asc("externalId").ignoreCase(),
                Sort.Order.asc("externalId")
        ), result.getSort().toList());
    }

    @Test
    void existingRawUniqueTieBreakerIsNotDuplicatedAfterCaseInsensitiveSort() {
        var requested = PageRequest.of(0, 20, Sort.by(
                Sort.Order.asc("externalId").ignoreCase(), Sort.Order.desc("externalId")));

        var result = PageableUtils.toStablePageable(
                requested, Set.of("externalId"), "externalId", 100);

        assertEquals(List.of(
                Sort.Order.asc("externalId").ignoreCase(),
                Sort.Order.desc("externalId")
        ), result.getSort().toList());
    }

    @Test
    void usesTheConfiguredUniqueFieldWhenTheRequestHasNoSort() {
        var result = PageableUtils.toStablePageable(
                PageRequest.of(0, 20), Set.of(), "transactionId", 100);

        assertEquals(List.of(Sort.Order.asc("transactionId")), result.getSort().toList());
    }

    @ParameterizedTest
    @CsvSource({"1, 1", "30, 30", "31, 30", "500, 30"})
    void limitsPageSizeToTheServiceMaximum(int requestedSize, int expectedSize) {
        var result = PageableUtils.toStablePageable(
                PageRequest.of(4, requestedSize), Set.of(), "id", 30);

        assertEquals(expectedSize, result.getPageSize());
        assertEquals(4, result.getPageNumber());
    }

    @Test
    void rejectsAnUnsupportedFieldEvenAfterAnAllowedSortField() {
        var requested = PageRequest.of(0, 20, Sort.by(
                Sort.Order.desc("createdAt"), Sort.Order.asc("credentials.password")));

        var error = assertThrows(BusinessException.class, () -> PageableUtils.toStablePageable(
                requested, Set.of("createdAt"), "id", 100));

        assertEquals(CommonErrorCode.INVALID_REQUEST, error.getErrorCode());
    }

    @Test
    void rejectsUnpagedRequestsInsteadOfAllowingUnboundedQueries() {
        var requested = Pageable.unpaged(Sort.by("createdAt"));

        var error = assertThrows(BusinessException.class, () -> PageableUtils.toStablePageable(
                requested, Set.of("createdAt"), "id", 100));

        assertEquals(CommonErrorCode.INVALID_REQUEST, error.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositivePageSizeLimitsAsConfigurationErrors(int maxPageSize) {
        assertThrows(IllegalArgumentException.class, () -> PageableUtils.toStablePageable(
                PageRequest.of(0, 20), Set.of(), "id", maxPageSize));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void rejectsBlankUniqueSortFieldsAsConfigurationErrors(String uniqueSortField) {
        assertThrows(IllegalArgumentException.class, () -> PageableUtils.toStablePageable(
                PageRequest.of(0, 20), Set.of(), uniqueSortField, 100));
    }
}
