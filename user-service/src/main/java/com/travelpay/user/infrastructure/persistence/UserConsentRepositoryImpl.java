package com.travelpay.user.infrastructure.persistence;

import java.util.List;

import com.travelpay.user.domain.model.entity.UserConsent;
import com.travelpay.user.domain.repository.UserConsentRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 응용 서비스의 트랜잭션에서 동의 기록을 신규 추가합니다. */
@Repository
@RequiredArgsConstructor
public class UserConsentRepositoryImpl implements UserConsentRepository {

    private final EntityManager entityManager;

    @Override
    public List<UserConsent> saveAll(List<UserConsent> consents) {
        // 식별자가 미리 정해진 동의 기록을 기존 행과 병합하지 않고 새로 추가합니다.
        consents.forEach(entityManager::persist);
        return consents;
    }
}
