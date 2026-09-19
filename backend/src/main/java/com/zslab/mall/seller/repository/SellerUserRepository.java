package com.zslab.mall.seller.repository;

import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.seller.entity.SellerUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerUserRepository extends JpaRepository<SellerUser, Long> {

    /**
     * userId가 어떤 seller의 구성원으로든 존재하는지 여부. role 종류 무관·SELLER 판정용. (Track 35 RBAC fail-closed)
     */
    boolean existsByUserId(Long userId);

    /** 셀러 소속 구성원 전량(Track 89-D 관리자 셀러 상세). seller 컬럼 경로 파생 쿼리. */
    List<SellerUser> findBySellerId(Long sellerId);

    /**
     * userId에 매핑된 seller.id를 해소한다. user_id 단독 UNIQUE(V12·Track 36 γ)로 최대 1건 보장 → Optional.
     * SellerActorResolver가 user.id→seller.id 단건 해소에 사용한다(passthrough 결함 교정·Phase 2).
     */
    @Query("SELECT su.seller.id FROM SellerUser su WHERE su.userId = :userId")
    Optional<Long> findSellerIdByUserId(@Param("userId") Long userId);

    /**
     * seller의 특정 역할(예: SELLER_OWNER) 구성원 user.id 목록(Track 85 정산 SMS 수신처 fallback). roleId는 Role Aggregate 논리참조라
     * theta-join(su.roleId = r.id)으로 코드 매칭한다. 모든 변수는 :sellerId·:roleCode 바인딩이다.
     */
    @Query("SELECT su.userId FROM SellerUser su, Role r WHERE su.seller.id = :sellerId AND su.roleId = r.id "
            + "AND r.code = :roleCode ORDER BY su.id")
    List<Long> findUserIdsBySellerIdAndRoleCode(@Param("sellerId") Long sellerId, @Param("roleCode") RoleCode roleCode);
}