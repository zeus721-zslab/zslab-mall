package com.zslab.mall.common.security;

import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.repository.UserRoleRepository;
import com.zslab.mall.seller.repository.SellerUserRepository;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@link RoleAuthorization} 실 구현 — role 위조 차단·fail-closed. 로그인 요청이 주장한 role을 DB 매핑 실조회로 검증한다.
 *
 * <p>해당 role 데이터가 부재하면 false(거부)로 닫는다(fail-closed). 판정 기준(현 2-도메인 스키마·신규 마이그레이션 없음):
 * <ul>
 *   <li>BUYER  → user_role에 (userId, code=BUYER) 존재</li>
 *   <li>ADMIN  → user_role에 (userId, code∈{SUPER_ADMIN, ADMIN_OPERATOR}) 존재</li>
 *   <li>SELLER → seller_user에 userId 행 존재(seller 내 role 종류 무관)</li>
 * </ul>
 *
 * <p>coarse 액터 3종({@link ActorRole})→세분 {@link RoleCode} 집합 매핑은 소비처가 여기 하나뿐이라 enum이 아닌 이 구현체
 * 내부 switch에 둔다(RoleCode enum 오염 방지). (Track 35 RBAC)
 */
@Component
public class DbRoleAuthorization implements RoleAuthorization {

    /** ADMIN 액터로 인정되는 세분 역할 코드 — 둘 중 하나라도 보유하면 통과. */
    private static final Set<RoleCode> ADMIN_CODES = EnumSet.of(RoleCode.SUPER_ADMIN, RoleCode.ADMIN_OPERATOR);

    private final UserRoleRepository userRoleRepository;
    private final SellerUserRepository sellerUserRepository;

    public DbRoleAuthorization(
            UserRoleRepository userRoleRepository,
            SellerUserRepository sellerUserRepository) {
        this.userRoleRepository = userRoleRepository;
        this.sellerUserRepository = sellerUserRepository;
    }

    @Override
    public boolean isAuthorized(Long actorId, ActorRole role) {
        // default 미사용 — ActorRole에 값이 추가되면 컴파일 에러로 강제 노출(fail-closed 판정 누락 방지).
        return switch (role) {
            case BUYER -> userRoleRepository.existsByUserIdAndRole_Code(actorId, RoleCode.BUYER);
            case ADMIN -> userRoleRepository.existsByUserIdAndRole_CodeIn(actorId, ADMIN_CODES);
            case SELLER -> sellerUserRepository.existsByUserId(actorId);
        };
    }
}
