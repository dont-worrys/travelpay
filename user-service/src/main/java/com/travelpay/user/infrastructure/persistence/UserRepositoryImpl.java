package com.travelpay.user.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/** 회원 저장·조회 계약을 Spring Data JPA에 연결합니다. */
@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository userJpaRepository;

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
}
