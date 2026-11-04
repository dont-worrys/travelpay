package com.travelpay.user.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.user.domain.model.entity.UserCredentials;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA가 구현하는 자체 로그인 수단 저장소입니다. */
public interface UserCredentialsJpaRepository extends JpaRepository<UserCredentials, UUID> {

    @Query("""
            select credentials
            from UserCredentials credentials
            join fetch credentials.user
            where credentials.loginEmail = :loginEmail
            """)
    Optional<UserCredentials> findByLoginEmail(@Param("loginEmail") String loginEmail);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select credentials
            from UserCredentials credentials
            join fetch credentials.user
            where credentials.userId = :userId
            """)
    Optional<UserCredentials> findByUserIdForUpdate(@Param("userId") UUID userId);

    boolean existsByLoginEmail(String loginEmail);
}
