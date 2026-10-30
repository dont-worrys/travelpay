package com.travelpay.user.infrastructure.persistence;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.stereotype.Repository;

/** 설정 클래스의 위치와 무관하게 테스트용 JPA 저장소와 구현체를 등록합니다. */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackageClasses = UserJpaRepository.class)
@ComponentScan(
        basePackageClasses = UserRepositoryImpl.class,
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(Repository.class))
public class JpaRepositoryTestConfiguration {
}
