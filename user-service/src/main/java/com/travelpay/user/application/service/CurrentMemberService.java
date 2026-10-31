package com.travelpay.user.application.service;

import java.util.Objects;
import java.util.UUID;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.user.domain.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** 현재 DB의 회원 존재·활동 상태·역할을 확인하는 공통 응용 기능입니다. */
@Service
public class CurrentMemberService {

    private final UserRepository userRepository;
    private final TransactionTemplate readTransactions;

    public CurrentMemberService(UserRepository userRepository, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.readTransactions = new TransactionTemplate(transactionManager);
        this.readTransactions.setReadOnly(true);
    }

    public AuthenticatedUser requireActive(UUID userId) {
        try {
            return Objects.requireNonNull(readTransactions.execute(status -> {
                var user = userRepository.findById(userId)
                        .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
                user.getStatus().requireActive();
                return new AuthenticatedUser(user.getId(), user.getRole());
            }));
        }
        catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
    }
}
