package com.travelpay.user.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** 날짜 어노테이션을 선언한 엔티티의 생성·수정 시각 기록을 활성화합니다. */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
public class JpaAuditingConfiguration {
}
