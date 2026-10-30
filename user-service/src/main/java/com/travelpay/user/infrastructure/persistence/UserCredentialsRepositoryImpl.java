package com.travelpay.user.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.entity.UserCredentials;
import com.travelpay.user.domain.repository.UserCredentialsRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/** 자체 로그인 수단의 저장·조회 계약을 Spring Data JPA에 연결합니다. */
@Repository
@RequiredArgsConstructor
public class UserCredentialsRepositoryImpl implements UserCredentialsRepository {

    private final UserCredentialsJpaRepository userCredentialsJpaRepository;

    @Override
    public UserCredentials save(UserCredentials credentials) {
        try {
            return userCredentialsJpaRepository.saveAndFlush(credentials);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "uk_user_credentials_login_email".equals(violation.getConstraintName())) {
                    throw new BusinessException(UserErrorCode.EMAIL_ALREADY_EXISTS, exception);
                }
            }
            throw exception;
        }
    }

    @Override
    public Optional<UserCredentials> findByLoginEmail(String loginEmail) {
        return userCredentialsJpaRepository.findByLoginEmail(loginEmail);
    }

    @Override
    public Optional<UserCredentials> findByUserId(UUID userId) {
        return userCredentialsJpaRepository.findById(userId);
    }

    @Override
    public boolean existsByLoginEmail(String loginEmail) {
        return userCredentialsJpaRepository.existsByLoginEmail(loginEmail);
    }
}
