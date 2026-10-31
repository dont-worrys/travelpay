package com.travelpay.user.infrastructure.persistence;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.stereotype.Repository;

/**
 * 설정 클래스의 위치와 무관하게 테스트용 JPA 저장소와 구현체를 등록합니다.
 * 실제 앱 컴포넌트 스캔에 섞이지 않도록 TestConfiguration으로 둡니다.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackageClasses = UserJpaRepository.class)
@ComponentScan(
        basePackageClasses = UserRepositoryImpl.class,
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(Repository.class))
public class JpaRepositoryTestConfiguration {
}
