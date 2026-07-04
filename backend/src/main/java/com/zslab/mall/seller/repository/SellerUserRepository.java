package com.zslab.mall.seller.repository;

import com.zslab.mall.seller.entity.SellerUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerUserRepository extends JpaRepository<SellerUser, Long> {

    /**
     * userId가 어떤 seller의 구성원으로든 존재하는지 여부. role 종류 무관·SELLER 판정용. (Track 35 RBAC fail-closed)
     */
    boolean existsByUserId(Long userId);

    /**
     * userId에 매핑된 seller.id를 해소한다. user_id 단독 UNIQUE(V12·Track 36 γ)로 최대 1건 보장 → Optional.
     * SellerActorResolver가 user.id→seller.id 단건 해소에 사용한다(passthrough 결함 교정·Phase 2).
     */
    @Query("SELECT su.seller.id FROM SellerUser su WHERE su.userId = :userId")
    Optional<Long> findSellerIdByUserId(@Param("userId") Long userId);
}