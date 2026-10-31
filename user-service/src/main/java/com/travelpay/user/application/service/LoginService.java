package com.travelpay.user.application.service;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.user.application.dto.LoginCommand;
import com.travelpay.user.application.dto.LoginResult;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.entity.UserCredentials;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.domain.repository.UserCredentialsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 일반 로그인에 필요한 자격 증명과 현재 회원 상태를 확인하고, 실패 횟수 제한을 적용합니다. */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final UserCredentialsRepository credentialsRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate readTransactions;
    private final LoginAttemptLimiter attemptLimiter;
    private final String missingMemberPasswordHash;

    public LoginService(UserCredentialsRepository credentialsRepository, PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager, LoginAttemptLimiter attemptLimiter) {
        this.credentialsRepository = credentialsRepository;
        this.passwordEncoder = passwordEncoder;
        this.attemptLimiter = attemptLimiter;
        this.readTransactions = new TransactionTemplate(transactionManager);
        this.readTransactions.setReadOnly(true);
        // 없는 이메일도 해시 비교를 수행해 자격 증명 실패 경로의 시간 차이를 줄입니다.
        this.missingMemberPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** 비밀번호 비교 중 DB 트랜잭션을 유지하지 않도록 기존 트랜잭션에서의 호출을 막습니다. */
    @Transactional(propagation = Propagation.NEVER)
    public LoginResult login(LoginCommand command) {
        if (command.loginEmail() == null || !isSupportedPassword(command.rawPassword())) {
            throw new BusinessException(UserErrorCode.INVALID_CREDENTIALS);
        }
        var email = command.loginEmail().strip().toLowerCase(Locale.ROOT);
        try {
            return loginWithinAttemptLimit(email, command.clientIp(), command.rawPassword());
        }
        catch (DataAccessException exception) {
            // 횟수를 확인·기록할 수 없으면 제한 없이 진행하지 않고 503으로 응답합니다.
            // 모든 로그인이 막히는 장애이므로 남기되, 이메일·IP가 담길 수 있는 메시지 대신 원인 종류만 기록합니다.
            log.error("로그인 시도 횟수 저장소 오류: {}", exception.getClass().getSimpleName());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
    }

    private LoginResult loginWithinAttemptLimit(String email, String clientIp, String rawPassword) {
        // 동시에 들어온 시도도 한도를 넘지 않도록 비밀번호 비교 전에 시도를 먼저 셉니다.
        if (!attemptLimiter.tryStart(email, clientIp)) {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        }
        LoginResult result;
        try {
            result = authenticate(email, rawPassword);
        }
        catch (RuntimeException exception) {
            if (exception instanceof BusinessException failure
                    && failure.getErrorCode() == UserErrorCode.INVALID_CREDENTIALS) {
                attemptLimiter.recordFailure(email, clientIp);
            }
            else {
                // 정지·종료 계정과 장애는 비밀번호를 맞히려는 실패가 아니므로 미리 센 시도를 뺍니다.
                attemptLimiter.cancel(email, clientIp);
            }
            throw exception;
        }
        attemptLimiter.recordSuccess(email, clientIp);
        return result;
    }

    private LoginResult authenticate(String email, String rawPassword) {
        var initial = findCredentials(email).orElse(null);
        var hash = initial == null ? missingMemberPasswordHash : initial.passwordHash();
        if (!matches(rawPassword, hash) || initial == null) {
            throw new BusinessException(UserErrorCode.INVALID_CREDENTIALS);
        }

        // 비교 중 비밀번호·회원 상태·권한이 바뀔 수 있어 로그인 확정 전에 다시 읽습니다.
        var current = findCredentials(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.INVALID_CREDENTIALS));
        if (!initial.member().userId().equals(current.member().userId())
                || !initial.passwordHash().equals(current.passwordHash())) {
            throw new BusinessException(UserErrorCode.INVALID_CREDENTIALS);
        }
        current.status().requireActive();
        return current.member();
    }

    private Optional<CredentialsSnapshot> findCredentials(String email) {
        try {
            return Objects.requireNonNull(readTransactions.execute(status ->
                    credentialsRepository.findByLoginEmail(email).map(CredentialsSnapshot::from)));
        }
        catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
    }

    private boolean matches(String rawPassword, String hash) {
        try {
            return passwordEncoder.matches(rawPassword, hash);
        }
        catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
    }

    private boolean isSupportedPassword(String rawPassword) {
        return rawPassword != null && rawPassword.length() <= 72 && rawPassword.matches("[!-~]+");
    }

    private record CredentialsSnapshot(LoginResult member, UserStatus status, String passwordHash) {

        private static CredentialsSnapshot from(UserCredentials credentials) {
            var user = credentials.getUser();
            return new CredentialsSnapshot(LoginResult.from(user), user.getStatus(), credentials.getPasswordHash());
        }

        @Override
        public String toString() {
            return "CredentialsSnapshot[REDACTED]";
        }
    }
}
