package com.travelpay.wallet.domain.resolution;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;

import java.util.Objects;
import java.util.UUID;

/**
 * 보정 시점에 확인한 한 소스(은행 출금·환불·원장)의 사실. lookup은 FOUND·NOT_FOUND만 저장된다
 * (조회 불가면 보정하지 않으므로). 계좌번호·응답 원문은 담지 않는다.
 * 컬럼 이름은 엔티티의 @AttributeOverride로 소스별로 정한다
 */
@Getter
@Embeddable
public class ResolutionEvidence {

    public static final String FOUND = "FOUND";
    public static final String NOT_FOUND = "NOT_FOUND";

    @Column(name = "lookup", nullable = false, updatable = false, length = 20)
    private String lookup;

    @Column(name = "tx_id", updatable = false)
    private UUID transactionId;

    @Column(name = "status", updatable = false, length = 20)
    private String status;

    @Column(name = "amount", updatable = false)
    private Long amount;

    protected ResolutionEvidence() {
    }

    private ResolutionEvidence(String lookup, UUID transactionId, String status, Long amount) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.transactionId = transactionId;
        this.status = status;
        this.amount = amount;
    }

    public static ResolutionEvidence found(UUID transactionId, String status, Long amount) {
        return new ResolutionEvidence(FOUND, transactionId, Objects.requireNonNull(status, "status"), amount);
    }

    public static ResolutionEvidence notFound() {
        return new ResolutionEvidence(NOT_FOUND, null, null, null);
    }
}
