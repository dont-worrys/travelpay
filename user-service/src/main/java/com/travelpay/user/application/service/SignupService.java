package com.travelpay.user.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.user.application.dto.SignupCommand;
import com.travelpay.user.application.dto.SignupResult;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.entity.TermsDocument;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.entity.UserConsent;
import com.travelpay.user.domain.model.entity.UserCredentials;
import com.travelpay.user.domain.model.vo.TermsDocumentId;
import com.travelpay.user.domain.repository.TermsDocumentRepository;
import com.travelpay.user.domain.repository.UserConsentRepository;
import com.travelpay.user.domain.repository.UserCredentialsRepository;
import com.travelpay.user.domain.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 일반 회원가입의 검증·비밀번호 해시·원자적 저장을 조정합니다. */
@Service
public class SignupService {

    private final UserRepository userRepository;
    private final UserCredentialsRepository credentialsRepository;
    private final TermsDocumentRepository termsRepository;
    private final UserConsentRepository consentRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final TransactionTemplate readTransactions;
    private final TransactionTemplate writeTransactions;

    public SignupService(UserRepository userRepository, UserCredentialsRepository credentialsRepository,
            TermsDocumentRepository termsRepository, UserConsentRepository consentRepository,
            PasswordEncoder passwordEncoder, Clock clock, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.credentialsRepository = credentialsRepository;
        this.termsRepository = termsRepository;
        this.consentRepository = consentRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.readTransactions = new TransactionTemplate(transactionManager);
        this.readTransactions.setReadOnly(true);
        this.writeTransactions = new TransactionTemplate(transactionManager);
    }

    /** 호출자의 기존 DB 트랜잭션이 비밀번호 해시 구간까지 이어지는 것을 막습니다. */
    @Transactional(propagation = Propagation.NEVER)
    public SignupResult signup(SignupCommand command) {
        var loginEmail = command.loginEmail().strip().toLowerCase(Locale.ROOT);
        try {
            readTransactions.executeWithoutResult(status ->
                    validateRegistration(loginEmail, command.phone(), command.consents(), clock.instant()));

            var passwordHash = passwordEncoder.encode(command.rawPassword());

            return Objects.requireNonNull(writeTransactions.execute(status ->
                    register(command, loginEmail, passwordHash)), "회원가입 결과가 필요합니다.");
        }
        catch (DataIntegrityViolationException exception) {
            // 이메일·휴대폰 중복은 저장소가 409로 바꿉니다. 그 밖의 제약 위반은 장애가 아니므로 그대로 전달합니다.
            throw exception;
        }
        catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
    }

    private SignupResult register(SignupCommand command, String loginEmail, String passwordHash) {
        var registeredAt = clock.instant();
        // 해시 계산 중 다른 가입이나 약관 시행이 있었을 수 있어 저장 직전에 다시 확인합니다.
        var agreedTerms = validateRegistration(loginEmail, command.phone(), command.consents(), registeredAt);
        var user = userRepository.save(User.register(command.realName(), command.phone(), registeredAt));
        credentialsRepository.save(UserCredentials.createFor(user, loginEmail, passwordHash, registeredAt));
        consentRepository.saveAll(agreedTerms.stream()
                .map(terms -> UserConsent.agreeTo(user, terms, registeredAt))
                .toList());
        return SignupResult.from(user);
    }

    private List<TermsDocument> validateRegistration(String loginEmail, String phone,
            List<TermsDocumentId> submittedConsents, Instant at) {
        if (credentialsRepository.existsByLoginEmail(loginEmail)) {
            throw new BusinessException(UserErrorCode.EMAIL_ALREADY_EXISTS);
        }
        if (userRepository.existsByPhone(phone)) {
            throw new BusinessException(UserErrorCode.PHONE_ALREADY_EXISTS);
        }

        var currentTerms = termsRepository.findEffectiveAt(at);
        if (currentTerms.isEmpty()) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        var termsById = currentTerms.stream()
                .collect(Collectors.toMap(TermsDocument::getId, Function.identity()));
        var submittedIds = new HashSet<TermsDocumentId>();
        for (var id : submittedConsents) {
            if (!submittedIds.add(id) || !termsById.containsKey(id)) {
                throw new BusinessException(UserErrorCode.TERMS_CONSENT_INVALID);
            }
        }
        if (currentTerms.stream().anyMatch(terms -> terms.isRequired() && !submittedIds.contains(terms.getId()))) {
            throw new BusinessException(UserErrorCode.TERMS_CONSENT_INVALID);
        }
        return submittedConsents.stream().map(termsById::get).toList();
    }
}
