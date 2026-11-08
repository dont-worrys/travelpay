package com.travelpay.user.infrastructure.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.repository.UserRepository;
import com.travelpay.user.domain.repository.UserSearchCondition;
import com.travelpay.user.domain.repository.UserSearchItem;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/** 회원 저장·조회 계약을 JPA로 구현합니다. */
@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository userJpaRepository;
    private final EntityManager entityManager;

    @Override
    public User save(User user) {
        try {
            // commit 전에 이 저장소에서 제약 오류를 확인하며, 호출자의 트랜잭션은 계속 유지합니다.
            return userJpaRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "uk_users_phone".equals(violation.getConstraintName())) {
                    throw new BusinessException(UserErrorCode.PHONE_ALREADY_EXISTS, exception);
                }
            }
            throw exception;
        }
    }

    @Override
    public Optional<User> findById(UUID userId) {
        return userJpaRepository.findById(userId);
    }

    @Override
    public boolean existsByPhone(String phone) {
        return userJpaRepository.existsByPhone(phone);
    }

    @Override
    public boolean increaseSessionVersion(UUID userId) {
        return userJpaRepository.increaseSessionVersion(userId) == 1;
    }

    @Override
    public List<User> findAllByIdForUpdate(Collection<UUID> userIds) {
        return userJpaRepository.findAllByIdForUpdate(userIds);
    }

    // 값이 있는 조건만 쿼리에 넣음. 값은 모두 바인딩 변수로 넘기고, 쿼리 문장은 고정된 조각으로만 만듦
    // 비밀번호 로그인 정보가 없는 회원도 빠지지 않게 LEFT JOIN 한 번으로 이메일까지 읽음(회원마다 추가 조회 없음)
    @Override
    public List<UserSearchItem> search(UserSearchCondition condition, int offset, int limit) {
        var filters = new ArrayList<String>();
        var parameters = new HashMap<String, Object>();
        if (condition.userId() != null) {
            filters.add("user.id = :userId");
            parameters.put("userId", condition.userId());
        }
        if (condition.loginEmail() != null) {
            filters.add("credentials.loginEmail = :loginEmail");
            parameters.put("loginEmail", condition.loginEmail());
        }
        if (condition.phone() != null) {
            filters.add("user.phone = :phone");
            parameters.put("phone", condition.phone());
        }
        if (condition.status() != null) {
            filters.add("user.status = :status");
            parameters.put("status", condition.status());
        }
        var where = filters.isEmpty() ? "" : " where " + String.join(" and ", filters);
        var query = entityManager.createQuery("""
                select new com.travelpay.user.domain.repository.UserSearchItem(
                    user.id, user.realName, credentials.loginEmail, user.phone, user.status, user.role)
                from User user
                left join UserCredentials credentials on credentials.user = user
                """ + where + " order by user.createdAt desc, user.id desc", UserSearchItem.class);
        parameters.forEach(query::setParameter);
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }
}
