package com.travelpay.user.domain.repository;

import java.time.Instant;
import java.util.List;

import com.travelpay.user.domain.model.entity.TermsDocument;

/** 약관 원본의 조회·추가 계약입니다. 게시한 버전은 고치거나 지우지 않습니다. */
public interface TermsDocumentRepository {

    /** 기준 시각까지 시행된 버전 중 시행 시각이 가장 늦은 약관을 코드별 하나씩, 코드 순서로 반환합니다. */
    List<TermsDocument> findEffectiveAt(Instant at);

    /** 관리자 약관 목록입니다. termsCode가 null이면 모든 코드를 대상으로, 코드 순·같은 코드 안에서는 시행 시각 최신순으로 offset부터 최대 limit개를 돌려줍니다. */
    List<TermsDocument> findPage(String termsCode, int offset, int limit);

    /** 새 약관 버전을 추가합니다. 같은 코드·버전이나 같은 코드·시행 시각이 이미 있으면 덮어쓰지 않고, 이 메서드에서 바로 업무 예외로 거절합니다. */
    void insert(TermsDocument document);
}
