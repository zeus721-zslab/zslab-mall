package com.zslab.mall.seller.repository;

import com.zslab.mall.seller.entity.SellerUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerUserRepository extends JpaRepository<SellerUser, Long> {

    /**
     * userId가 어떤 seller의 구성원으로든 존재하는지 여부. role 종류 무관·SELLER 판정용. (Track 35 RBAC fail-closed)
     */
    boolean existsByUserId(Long userId);
}
