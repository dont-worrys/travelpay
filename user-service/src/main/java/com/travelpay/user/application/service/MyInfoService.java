package com.travelpay.user.application.service;

import java.util.Objects;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.user.application.dto.MyInfoResult;
import com.travelpay.user.domain.repository.UserCredentialsRepository;
import com.travelpay.user.domain.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** 로그인한 회원 본인의 마이페이지 정보를 조회합니다. */
@Service
public class MyInfoService {

    private final UserRepository userRepository;
    private final UserCredentialsRepository userCredentialsRepository;
    private final TransactionTemplate readTransactions;

    public MyInfoService(UserRepository userRepository, UserCredentialsRepository userCredentialsRepository,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.userCredentialsRepository = userCredentialsRepository;
        this.readTransactions = new TransactionTemplate(transactionManager);
        this.readTransactions.setReadOnly(true);
    }

    /** 트랜잭션 시작·종료 실패까지 503으로 바꾸도록 트랜잭션을 이 메서드 안에서 실행합니다. */
    public MyInfoResult getMyInfo(UUID userId) {
        try {
            return Objects.requireNonNull(readTransactions.execute(status -> {
                // 인가 단계에서 회원을 확인한 뒤 그 사이에 삭제됐다면, 로그인 세션이 없는 것과 같게 처리합니다.
                var user = userRepository.findById(userId)
                        .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
                return MyInfoResult.from(user, userCredentialsRepository.findByUserId(userId).orElse(null));
            }));
        }
        catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
    }
}
