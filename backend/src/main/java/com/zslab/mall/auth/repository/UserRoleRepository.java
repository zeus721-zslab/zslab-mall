package com.zslab.mall.auth.repository;

import com.zslab.mall.auth.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRole, Long> {
}
