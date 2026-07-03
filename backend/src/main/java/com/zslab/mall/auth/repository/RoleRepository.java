package com.zslab.mall.auth.repository;

import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.enums.RoleCode;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    /** RoleCode로 seed된 Role 조회. role.code는 uk_role_code UNIQUE(V11)로 최대 1건. (Track 34) */
    Optional<Role> findByCode(RoleCode code);
}
