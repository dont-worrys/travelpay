package com.travelpay.user.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import com.travelpay.common.error.BusinessException;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.entity.TermsDocument;
import com.travelpay.user.domain.repository.TermsDocumentRepository;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;

/** 약관 원본의 조회·추가 계약을 JPA로 구현합니다. */
@Repository
@RequiredArgsConstructor
public class TermsDocumentRepositoryImpl implements TermsDocumentRepository {

    private final TermsDocumentJpaRepository termsDocumentJpaRepository;
    private final EntityManager entityManager;

    @Override
    public List<TermsDocument> findEffectiveAt(Instant at) {
        return termsDocumentJpaRepository.findEffectiveAt(at);
    }

    @Override
    public List<TermsDocument> findPage(String termsCode, int offset, int limit) {
        var query = termsCode == null
                ? entityManager.createQuery("""
                        select terms from TermsDocument terms
                        order by terms.id.termsCode, terms.effectiveAt desc
                        """, TermsDocument.class)
                : entityManager.createQuery("""
                        select terms from TermsDocument terms
                        where terms.id.termsCode = :termsCode
                        order by terms.effectiveAt desc
                        """, TermsDocument.class).setParameter("termsCode", termsCode);
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    // save()는 키가 채워진 약관을 이미 있는 데이터로 보고 merge함. 열이 모두 수정 불가라 같은 버전이 있으면
    // 아무것도 저장하지 않고 성공처럼 끝나므로, persist로 INSERT만 함
    // flush로 이 자리에서 제약 위반을 확인하고, 호출자의 트랜잭션은 그대로 둠
    @Override
    public void insert(TermsDocument document) {
        try {
            entityManager.persist(document);
            entityManager.flush();
        }
        catch (EntityExistsException exception) {
            // 같은 트랜잭션이 그 버전을 이미 읽었거나 추가해 두었으면 DB에 가기 전에 여기서 거절됨
            throw new BusinessException(UserErrorCode.TERMS_VERSION_CONFLICT, exception);
        }
        catch (PersistenceException exception) {
            // EntityManager를 직접 쓰므로, Spring의 DataAccessException으로 바뀌기 전 JPA 예외로 받음
            // 이 표의 unique 제약(기본키, 코드+시행 시각)은 모두 버전 충돌이라 위반 종류로 판단함.
            // 제약 이름은 DB 메시지 문구에서 꺼내 언어 설정에 따라 비지만, 종류는 오류 코드(23505)로 정해짐
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE) {
                    throw new BusinessException(UserErrorCode.TERMS_VERSION_CONFLICT, exception);
                }
            }
            throw exception;
        }
    }
}
