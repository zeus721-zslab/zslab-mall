package com.zslab.mall.demoseed.service;

import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.exception.SuperAdminRequiredException;
import com.zslab.mall.auth.repository.UserRoleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 데모 시더 API의 SUPER_ADMIN 세분 인가(D-244·D-245). JWT는 coarse ADMIN만 담으므로 user_role 실조회로 확인한다(AdminOperatorProvisioningService
 * 선례 · dryRun 포함).
 */
@Slf4j
@Component
public class DemoSeedAuthorization {

    private final UserRoleRepository userRoleRepository;

    public DemoSeedAuthorization(UserRoleRepository userRoleRepository) {
        this.userRoleRepository = userRoleRepository;
    }

    /** @throws SuperAdminRequiredException 호출자가 SUPER_ADMIN이 아닐 때(403) */
    public void requireSuperAdmin(Long callerUserId) {
        if (!userRoleRepository.existsByUserIdAndRole_Code(callerUserId, RoleCode.SUPER_ADMIN)) {
            log.warn("[DemoSeed] SUPER_ADMIN 아님 차단(403) callerUserId={}", callerUserId);
            throw new SuperAdminRequiredException("SUPER_ADMIN만 데모 데이터를 적재할 수 있습니다.");
        }
    }
}
