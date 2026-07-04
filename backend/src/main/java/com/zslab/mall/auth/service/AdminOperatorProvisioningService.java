package com.zslab.mall.auth.service;

import com.zslab.mall.auth.controller.request.AdminOperatorProvisioningRequest;
import com.zslab.mall.auth.controller.response.AdminOperatorProvisioningResponse;
import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.entity.UserRole;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.exception.AdminOperatorAlreadyExistsException;
import com.zslab.mall.auth.exception.SuperAdminRequiredException;
import com.zslab.mall.auth.repository.RoleRepository;
import com.zslab.mall.auth.repository.UserRoleRepository;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.exception.UserNotFoundException;
import com.zslab.mall.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영 관리자(ADMIN_OPERATOR) 공급 Application Service(Track 38·SUPER_ADMIN 주도). 기존 회원에 ADMIN_OPERATOR 역할을
 * 부여한다(신규 회원 생성 아님). 트랜잭션 경계는 메서드 단위다.
 *
 * <p><b>인가(403)</b>: JWT는 coarse ActorRole(BUYER/SELLER/ADMIN)만 담아 SUPER_ADMIN·ADMIN_OPERATOR를 구분하는
 * authority가 없다. 따라서 SecurityConfig {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}는 코어스 1차 게이트일 뿐이며,
 * "SUPER_ADMIN만 공급 가능"이라는 세분 규칙은 본 서비스 진입 시 caller의 user_role을 실조회해 강제한다(fail-closed).
 * 인가를 대상 존재 확인보다 먼저 수행해 비-SUPER_ADMIN에게 대상 회원 존재 여부를 노출하지 않는다.
 *
 * <p><b>중복 부여(409)</b>: uk_user_role(user_id, role_id)(V1)를 최종 방어선으로 삼는다(Track 37 옵션 A 패턴 정합). pre-check
 * 대신 {@code saveAndFlush}가 던지는 {@link DataIntegrityViolationException}을 409로 변환한다.
 *
 * <p>role 배선은 seed된 ADMIN_OPERATOR Role(V11)을 재사용한다({@link com.zslab.mall.user.service.UserService} BUYER 패턴 정합).
 */
@Slf4j
@Service
@Transactional
public class AdminOperatorProvisioningService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;

    public AdminOperatorProvisioningService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
    }

    /**
     * 대상 회원에 ADMIN_OPERATOR 역할을 부여한다. 성공 시 대상 회원의 public_id를 반환한다.
     *
     * @param callerUserId 요청을 수행한 인증 액터의 userId(SUPER_ADMIN 여부 검증 대상)
     * @param request 대상 회원 userId를 담은 요청
     * @throws SuperAdminRequiredException caller가 SUPER_ADMIN이 아닌 경우(403)
     * @throws UserNotFoundException 대상 userId에 해당하는 User가 없는 경우(404)
     * @throws IllegalStateException ADMIN_OPERATOR Role seed가 없는 경우(내부 오류·500)
     * @throws AdminOperatorAlreadyExistsException 대상이 이미 ADMIN_OPERATOR 역할을 보유한 경우(409·uk_user_role 위반)
     */
    public AdminOperatorProvisioningResponse provision(Long callerUserId, AdminOperatorProvisioningRequest request) {
        if (!userRoleRepository.existsByUserIdAndRole_Code(callerUserId, RoleCode.SUPER_ADMIN)) {
            log.warn("[AdminOperatorProvisioning] SUPER_ADMIN 아님 차단(403) callerUserId={}", callerUserId);
            throw new SuperAdminRequiredException("SUPER_ADMIN만 운영 관리자를 공급할 수 있습니다.");
        }

        User target = userRepository.findById(request.userId())
                .orElseThrow(() -> new UserNotFoundException(
                        "운영 관리자로 지정한 User가 없습니다: userId=" + request.userId()));

        Role operatorRole = roleRepository.findByCode(RoleCode.ADMIN_OPERATOR)
                .orElseThrow(() -> new IllegalStateException("ADMIN_OPERATOR Role seed 누락(V11 마이그레이션 확인 필요)."));

        try {
            // saveAndFlush로 uk_user_role(user_id, role_id)(V1) 위반을 트랜잭션 내에서 즉시 표면화한다. 위반 시 아래 catch가
            // 409로 변환하며, @Transactional 경계에서 롤백한다.
            userRoleRepository.saveAndFlush(UserRole.create(target.getId(), operatorRole));
        } catch (DataIntegrityViolationException exception) {
            log.warn("[AdminOperatorProvisioning] 중복 부여 차단(409) targetUserId={}", request.userId());
            throw new AdminOperatorAlreadyExistsException(
                    "이미 운영 관리자 역할을 보유한 사용자입니다: userId=" + request.userId());
        }

        log.info("[AdminOperatorProvisioning] 운영 관리자 공급 완료 targetUserPublicId={} byCallerUserId={}",
                target.getPublicId(), callerUserId);
        return new AdminOperatorProvisioningResponse(target.getPublicId());
    }
}
