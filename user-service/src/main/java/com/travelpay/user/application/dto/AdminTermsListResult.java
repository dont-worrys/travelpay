package com.travelpay.user.application.dto;

import java.util.List;

/** 관리자 약관 목록의 한 페이지입니다. 전체 개수 대신 다음 페이지가 있는지만 알려 줍니다. */
public record AdminTermsListResult(List<TermsResult> items, int page, int size, boolean hasNext) {
}
