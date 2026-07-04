package com.zslab.mall.auth.repository;

import com.zslab.mall.auth.entity.UserRole;
import com.zslab.mall.auth.enums.RoleCode;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    /**
     * userId가 code 역할을 보유하는지 여부. role.code 중첩 프로퍼티(Role_Code) traverse로 seed-id 하드코딩 없이 조회. (Track 35 RBAC fail-closed)
     */
    boolean existsByUserIdAndRole_Code(Long userId, RoleCode code);

    /**
     * userId가 codes 중 하나 이상의 역할을 보유하는지 여부. ADMIN 판정(SUPER_ADMIN·ADMIN_OPERATOR IN)용. (Track 35)
     */
    boolean existsByUserIdAndRole_CodeIn(Long userId, Collection<RoleCode> codes);

    /**
     * user 무관·code 역할을 보유한 회원이 1명이라도 존재하는지 여부. 최초 SUPER_ADMIN 부트스트랩 멱등 판정용
     * (SUPER_ADMIN 1명 이상이면 공급 skip). role.code 중첩 프로퍼티 traverse로 seed-id 하드코딩 없이 조회. (Track 38)
     */
    boolean existsByRole_Code(RoleCode code);
}
