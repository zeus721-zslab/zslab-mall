package com.zslab.mall.auth.repository;

import com.zslab.mall.auth.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {
}
