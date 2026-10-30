package com.travelpay.user.domain.repository;

import java.time.Instant;
import java.util.List;

import com.travelpay.user.domain.model.entity.TermsDocument;

/** 약관 원본의 조회 계약입니다. */
public interface TermsDocumentRepository {

    /** 기준 시각까지 시행된 버전 중 시행 시각이 가장 늦은 약관을 코드별 하나씩, 코드 순서로 반환합니다. */
    List<TermsDocument> findEffectiveAt(Instant at);
}
