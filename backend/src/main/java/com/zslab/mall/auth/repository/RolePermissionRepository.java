package com.zslab.mall.auth.repository;

import com.zslab.mall.auth.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {
}
