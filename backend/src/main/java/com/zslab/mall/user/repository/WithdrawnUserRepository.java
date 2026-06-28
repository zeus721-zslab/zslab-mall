package com.zslab.mall.user.repository;

import com.zslab.mall.user.entity.WithdrawnUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WithdrawnUserRepository extends JpaRepository<WithdrawnUser, Long> {
}
