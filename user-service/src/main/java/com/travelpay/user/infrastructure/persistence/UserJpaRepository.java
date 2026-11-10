package com.travelpay.user.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.travelpay.user.domain.model.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA가 구현하는 회원 저장소입니다. */
public interface UserJpaRepository extends JpaRepository<User, UUID> {

    boolean existsByPhone(String phone);

    // 읽고 더해서 저장하지 않고 UPDATE 한 문장으로 올려서, 동시에 올린 값이 사라지지 않게 함
    // 이미 읽어 둔 엔티티의 sessionVersion은 그대로이므로, 올린 뒤 값이 필요하면 다시 조회해야 함
    @Modifying
    @Query("update User user set user.sessionVersion = user.sessionVersion + 1 where user.id = :userId")
    int increaseSessionVersion(@Param("userId") UUID userId);

    // 두 관리자가 서로를 동시에 정지해도 교착되지 않도록 항상 ID 순서로 잠금
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.id in :userIds order by user.id")
    List<User> findAllByIdForUpdate(@Param("userIds") Collection<UUID> userIds);
}
