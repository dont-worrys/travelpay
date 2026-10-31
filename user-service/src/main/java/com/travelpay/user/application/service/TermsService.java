package com.travelpay.user.application.service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.user.application.dto.TermsResult;
import com.travelpay.user.domain.repository.TermsDocumentRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** 주입받은 시계를 기준으로 현재 시행 중인 약관을 조회합니다. */
@Service
public class TermsService {

    private final TermsDocumentRepository termsDocumentRepository;
    private final Clock clock;
    private final TransactionTemplate readTransactions;

    public TermsService(TermsDocumentRepository termsDocumentRepository, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.termsDocumentRepository = termsDocumentRepository;
        this.clock = clock;
        this.readTransactions = new TransactionTemplate(transactionManager);
        this.readTransactions.setReadOnly(true);
    }

    /** 트랜잭션 시작·종료 실패까지 503으로 바꾸도록 트랜잭션을 이 메서드 안에서 실행합니다. */
    public List<TermsResult> getCurrentTerms() {
        var now = clock.instant();
        try {
            return Objects.requireNonNull(readTransactions.execute(status ->
                    termsDocumentRepository.findEffectiveAt(now).stream()
                            .map(TermsResult::from)
                            .toList()));
        }
        catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
    }
}
