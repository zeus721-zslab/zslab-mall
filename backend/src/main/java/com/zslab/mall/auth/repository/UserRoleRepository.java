package com.zslab.mall.auth.repository;

import com.zslab.mall.auth.entity.UserRole;
import com.zslab.mall.auth.enums.RoleCode;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

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

    /**
     * code 역할을 보유한 회원 수. 마지막 SUPER_ADMIN 회수 방어(count &lt;= 1 차단)에 정확한 인원수가 필요하다 —
     * {@code existsByRole_Code}는 1명 이상 여부만 알려 마지막 1명을 식별하지 못한다(Track 53). 반드시
     * {@link RoleRepository#findByCodeForUpdate}로 Role 행을 잠근 뒤 호출해 동시 회수 간 count-then-delete를 직렬화한다.
     */
    long countByRole_Code(RoleCode code);

    /**
     * userId가 보유한 code 역할 매핑을 HARD delete하고 삭제된 행 수를 반환한다(Track 53 권한 회수·AUTH-4).
     *
     * <p>find→delete 사이의 TOCTOU 창을 없애기 위해 조회 없이 단일 delete 쿼리로 수행한다. 반환값 0은 대상이 해당
     * 역할을 보유하지 않음(User 미존재·경합 선삭제 포함)을 뜻하며 서비스가 404로 변환한다. 반환값 1은 삭제 성공이다
     * (uk_user_role(user_id, role_id)로 (userId, code)당 최대 1행).
     *
     * <p>파생 쿼리명(deleteBy*) 대신 @Query를 명시해 role.code 중첩 traverse를 JPQL로 드러낸다. before diff는 삭제한
     * 매핑의 roleCode만으로 구성한다 — 현 UserRole 모델이 roleCode 외 감사 대상 상태를 갖지 않기 때문이다(부여 메타
     * 컬럼 추가 시 재검토).
     */
    @Modifying
    @Query("DELETE FROM UserRole ur WHERE ur.userId = :userId AND ur.role.code = :code")
    int deleteByUserIdAndRoleCode(Long userId, RoleCode code);
}
