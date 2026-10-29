package com.travelpay.exchange.domain.shared;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

/**
 * 생성·수정 시각을 자동으로 채우는 엔티티의 부모입니다. 시각은 서비스의 Clock 빈 기준입니다.
 * 감사 리스너는 META-INF/orm.xml에서 모든 엔티티에 등록하므로, 도메인은 Spring Data 어노테이션만 압니다.
 * 바뀌지 않는 이력(exchange_rates)은 상속하지 않습니다.
 */
@MappedSuperclass
@Getter
public abstract class BaseTimeEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
