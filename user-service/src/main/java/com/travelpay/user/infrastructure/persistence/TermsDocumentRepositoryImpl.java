package com.travelpay.user.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import com.travelpay.user.domain.model.entity.TermsDocument;
import com.travelpay.user.domain.repository.TermsDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 약관 원본 조회 계약을 Spring Data JPA에 연결합니다. */
@Repository
@RequiredArgsConstructor
public class TermsDocumentRepositoryImpl implements TermsDocumentRepository {

    private final TermsDocumentJpaRepository termsDocumentJpaRepository;

    @Override
    public List<TermsDocument> findEffectiveAt(Instant at) {
        return termsDocumentJpaRepository.findEffectiveAt(at);
    }
}
