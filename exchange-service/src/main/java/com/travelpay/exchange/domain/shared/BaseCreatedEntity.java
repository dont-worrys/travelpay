package com.travelpay.exchange.domain.shared;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;

/**
 * 생성 시각만 자동으로 채우는 엔티티의 부모입니다. INSERT만 하고 수정하지 않는 이력에 씁니다.
 * 시각은 서비스의 Clock 빈 기준이며, 감사 리스너는 META-INF/orm.xml에서 등록합니다.
 */
@MappedSuperclass
@Getter
public abstract class BaseCreatedEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
