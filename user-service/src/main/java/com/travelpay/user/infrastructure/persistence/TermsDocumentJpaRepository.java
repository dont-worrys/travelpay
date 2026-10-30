package com.travelpay.user.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import com.travelpay.user.domain.model.entity.TermsDocument;
import com.travelpay.user.domain.model.vo.TermsDocumentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA가 구현하는 시행 중인 약관 조회 저장소입니다. */
public interface TermsDocumentJpaRepository extends JpaRepository<TermsDocument, TermsDocumentId> {

    @Query("""
            select terms
            from TermsDocument terms
            where terms.effectiveAt = (
                select max(candidate.effectiveAt)
                from TermsDocument candidate
                where candidate.id.termsCode = terms.id.termsCode
                  and candidate.effectiveAt <= :at)
            order by terms.id.termsCode
            """)
    List<TermsDocument> findEffectiveAt(@Param("at") Instant at);
}
