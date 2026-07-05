package com.zslab.mall.auth.repository;

import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.enums.RoleCode;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RoleRepository extends JpaRepository<Role, Long> {

    /** RoleCode로 seed된 Role 조회. role.code는 uk_role_code UNIQUE(V11)로 최대 1건. (Track 34) */
    Optional<Role> findByCode(RoleCode code);

    /**
     * RoleCode로 Role 행을 SELECT ... FOR UPDATE로 잠가 조회한다(Track 53 last-SUPER_ADMIN 회수 직렬화용).
     *
     * <p>마지막 SUPER_ADMIN 회수 방어는 "count 판정 → delete"의 두 단계가 원자적이어야 한다. 동시 회수 요청이
     * 이 락 없이 진행되면 각자 count=1을 읽고 둘 다 삭제해 시스템 락아웃이 발생한다. role.code는 uk_role_code
     * UNIQUE(V11)로 단일 행이므로 SUPER_ADMIN Role 행 하나를 잠그면 모든 동시 회수가 동일 행에서 직렬화된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Role r WHERE r.code = :code")
    Optional<Role> findByCodeForUpdate(RoleCode code);
}
