package com.zslab.mall.seller.repository;

import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.seller.entity.SellerUser;
import com.zslab.mall.seller.enums.SellerStatus;
import java.util.Collection;
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

    /**
     * userId가 {@code statuses} 상태의 seller 구성원으로 존재하는지(Track 90-A 셀러 로그인 상태 가드·role 종류 무관).
     * seller 조인이라 soft-delete 셀러({@code @SQLRestriction})는 제외되며, 다중 소속이 생겨도 허용 상태 셀러가 하나라도 있으면 true다
     * (현재는 uk_seller_user_user_id·V12로 최대 1건).
     */
    boolean existsByUserIdAndSeller_StatusIn(Long userId, Collection<SellerStatus> statuses);

    /** 셀러 소속 구성원 전량(Track 89-D 관리자 셀러 상세). seller 컬럼 경로 파생 쿼리. */
    List<SellerUser> findBySellerId(Long sellerId);

    /** user가 소속된 구성원 행(Track 89-G). user_id 단독 UNIQUE(V12)로 최대 1건 — 추가 전 "이미 소속" 선검사(같은 셀러·타 셀러 구분)용. */
    Optional<SellerUser> findByUserId(Long userId);

    /** 특정 셀러의 특정 구성원 행(Track 89-G 제거·역할 변경 대상 해소). 타 셀러 소속이면 empty → 404(존재 은닉). */
    Optional<SellerUser> findBySellerIdAndUserId(Long sellerId, Long userId);

    /**
     * 셀러의 활성 구성원 수(역할 무관·Track 89-G STEP 498 회원 상세 lastActiveMember). 활성 = user 행 존재 ∧ soft-delete 아님 ∧ 미탈퇴.
     * userId는 논리참조(D-01)라 theta-join(su.userId = u.id)한다. 모든 변수는 :sellerId 바인딩이다.
     */
    @Query("SELECT COUNT(su) FROM SellerUser su, User u WHERE su.seller.id = :sellerId AND su.userId = u.id "
            + "AND u.deletedAt IS NULL AND u.withdrawnAt IS NULL")
    long countActiveBySellerId(@Param("sellerId") Long sellerId);

    /**
     * userId에 매핑된 seller.id를 해소한다. user_id 단독 UNIQUE(V12·Track 36 γ)로 최대 1건 보장 → Optional.
     * SellerActorResolver가 user.id→seller.id 단건 해소에 사용한다(passthrough 결함 교정·Phase 2).
     */
    @Query("SELECT su.seller.id FROM SellerUser su WHERE su.userId = :userId")
    Optional<Long> findSellerIdByUserId(@Param("userId") Long userId);

    /**
     * userId에 매핑된 seller.id와 status를 함께 해소한다(Track 90-A 셀러 상태 가드·SellerActorResolver용). seller를 명시 조인하므로
     * seller 행 부재·soft-delete({@code @SQLRestriction})면 empty(fail-closed) — FK 컬럼만 읽는 {@link #findSellerIdByUserId}와 다르다.
     * user_id 단독 UNIQUE(V12)로 최대 1건 → Optional. 모든 변수는 :userId 바인딩이다.
     */
    @Query("SELECT s.id AS sellerId, s.status AS status FROM SellerUser su JOIN su.seller s WHERE su.userId = :userId")
    Optional<SellerMembershipProjection> findMembershipByUserId(@Param("userId") Long userId);

    /**
     * seller의 특정 역할(예: SELLER_OWNER) 구성원 user.id 목록(Track 85 정산 SMS 수신처 fallback). roleId는 Role Aggregate 논리참조라
     * theta-join(su.roleId = r.id)으로 코드 매칭한다. 모든 변수는 :sellerId·:roleCode 바인딩이다.
     */
    @Query("SELECT su.userId FROM SellerUser su, Role r WHERE su.seller.id = :sellerId AND su.roleId = r.id "
            + "AND r.code = :roleCode ORDER BY su.id")
    List<Long> findUserIdsBySellerIdAndRoleCode(@Param("sellerId") Long sellerId, @Param("roleCode") RoleCode roleCode);
}