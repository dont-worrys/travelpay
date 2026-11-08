package com.travelpay.user.presentation.dto;

import java.util.List;

import com.travelpay.user.application.dto.AdminTermsListResult;

/** 관리자 약관 목록 조회 응답입니다. 각 약관은 공개 약관 조회와 같은 형식으로 돌려줍니다. */
public record AdminTermsListResponse(List<TermsResponse.TermItem> items, int page, int size, boolean hasNext) {

    public static AdminTermsListResponse from(AdminTermsListResult result) {
        return new AdminTermsListResponse(result.items().stream().map(TermsResponse.TermItem::from).toList(),
                result.page(), result.size(), result.hasNext());
    }
}
