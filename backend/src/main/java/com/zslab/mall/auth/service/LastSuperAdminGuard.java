package com.zslab.mall.auth.service;

import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.exception.LastSuperAdminRevocationException;
import com.zslab.mall.auth.repository.RoleRepository;
import com.zslab.mall.auth.repository.UserRoleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마지막 슈퍼 관리자 보호(D-230·일반 규칙). 본인 탈퇴·관리자 탈퇴·SUPER_ADMIN 역할 회수 3경로가 같은 판정을 쓴다.
 *
 * <p>대상이 SUPER_ADMIN일 때만 SUPER_ADMIN Role 행(uk_role_code 단일 행)을 잠가 세 경로의 판정·변경을 직렬화한다. 일반 회원
 * 탈퇴는 잠금을 잡지 않는다. 잠금 뒤 대상이 활성 보유자인지와 활성 인원을 다시 읽으므로(READ_COMMITTED) 동시 요청이 각자
 * 2명을 읽고 둘 다 통과하는 경합이 없다. 호출자 트랜잭션 안에서만 의미가 있어(잠금이 커밋까지 유지돼야 함) MANDATORY다.
 */
@Slf4j
@Component
public class LastSuperAdminGuard {

    static final String LAST_SUPER_ADMIN_MESSAGE = "마지막 슈퍼 관리자는 탈퇴하거나 권한을 해제할 수 없습니다.";

    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;

    public LastSuperAdminGuard(RoleRepository roleRepository, UserRoleRepository userRoleRepository) {
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
    }

    /**
     * userId가 활성 SUPER_ADMIN이고 활성 SUPER_ADMIN이 그 1명뿐이면 거부한다. 탈퇴한 SUPER_ADMIN은 인원에 넣지 않는다.
     *
     * @throws LastSuperAdminRevocationException 마지막 활성 슈퍼 관리자의 탈퇴·SUPER_ADMIN 회수 시(409)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireNotLastSuperAdmin(Long userId) {
        // SUPER_ADMIN 부여 API가 없어 잠금 전 보유 판정이 뒤집히는 경합은 회수뿐이며, 그 경우 잠금 뒤 재확인이 걸러낸다.
        if (!userRoleRepository.existsByUserIdAndRole_Code(userId, RoleCode.SUPER_ADMIN)) {
            return;
        }
        // 반환값은 락 획득이 목적.
        roleRepository.findByCodeForUpdate(RoleCode.SUPER_ADMIN)
                .orElseThrow(() -> new IllegalStateException("SUPER_ADMIN Role seed 누락(V11 마이그레이션 확인 필요)."));
        if (!userRoleRepository.existsActiveByUserIdAndRoleCode(userId, RoleCode.SUPER_ADMIN)) {
            return; // 이미 탈퇴한 SUPER_ADMIN의 역할 정리는 활성 인원을 줄이지 않는다.
        }
        if (userRoleRepository.countActiveByRoleCode(RoleCode.SUPER_ADMIN) <= 1) {
            log.warn("[LastSuperAdmin] 마지막 슈퍼 관리자 탈퇴·회수 차단(409) userId={}", userId);
            throw new LastSuperAdminRevocationException(LAST_SUPER_ADMIN_MESSAGE);
        }
    }
}
