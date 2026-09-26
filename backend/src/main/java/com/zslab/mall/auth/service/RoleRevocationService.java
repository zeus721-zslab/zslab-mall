package com.zslab.mall.auth.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.exception.LastSuperAdminRevocationException;
import com.zslab.mall.auth.exception.RoleAssignmentNotFoundException;
import com.zslab.mall.auth.exception.SelfRoleRevocationException;
import com.zslab.mall.auth.exception.SuperAdminRequiredException;
import com.zslab.mall.auth.repository.UserRoleRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.security.DemoAccountGuard;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.repository.UserRepository;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 권한 회수 Application Service(Track 53·SUPER_ADMIN 주도·AUTH-4). 대상 회원의 역할 매핑을 HARD delete하고 회수 이력을
 * AuditLog로 보존한다. 트랜잭션 경계는 메서드 단위다({@link AdminOperatorProvisioningService} 정합).
 *
 * <p><b>인가(403)</b>: 부여와 동일하게 SecurityConfig {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}는 코어스
 * 1차 게이트일 뿐이며, "SUPER_ADMIN만 회수 가능"이라는 세분 규칙은 진입 시 caller의 user_role을 실조회해 강제한다
 * (fail-closed). 인가를 대상 존재 확인·public_id 해소보다 먼저 수행해 비-SUPER_ADMIN에게 대상 회원 존재 여부를
 * 노출하지 않는다(AdminOperatorProvisioningService 정보은닉 원칙 정합).
 *
 * <p><b>대상 식별</b>: 외부 노출은 public_id(usr_)를 쓰는 path-variable 관습을 따르되(전 컨트롤러 정합), 게이트 이후
 * 서비스 내부에서 userId(BIGINT)로 해소해 감사 target_id·delete에 사용한다. 해소 실패(미존재·소프트삭제)는 역할 미보유와
 * 함께 404로 통합 은닉한다.
 *
 * <p><b>self-revoke 차단(403)</b>: caller는 항상 SUPER_ADMIN이므로, 자기 대상 회수 중 위험한 경우는 자신의 SUPER_ADMIN
 * 강등(락아웃·불가역 실수)뿐이다. 자신의 ADMIN_OPERATOR 자기회수는 SUPER_ADMIN 자격이 유지돼 무해하므로 차단하지 않는다
 * (과잉방어 회피). 따라서 self 검사는 회수 대상 roleCode가 SUPER_ADMIN일 때만 적용한다.
 *
 * <p><b>마지막 SUPER_ADMIN 방어(409)</b>: SUPER_ADMIN 0명이 되면 시스템 락아웃이 발생한다. count 판정과 delete가
 * 원자적이어야 하므로 {@link LastSuperAdminGuard}가 SUPER_ADMIN Role 행을 잠근 뒤 활성 인원수를 센다(탈퇴 경로와 공유·D-230).
 * uk_role_code 단일 행이라 동시 회수가 동일 행에서 직렬화된다.
 *
 * <p><b>회수·감사</b>: find→delete TOCTOU를 없애기 위해 조회 없이 {@code deleteByUserIdAndRoleCode} 단일 delete로
 * 수행한다. 0 row는 미보유(404)로, 1 row는 감사 적재로 이어진다. before diff는 회수한 roleCode만 담고 after는 사유만 담아
 * DiffBuilder가 role 키 삭제·reason 키 추가를 diff로 잡게 한다(ProductApprovalService 감사 배선 선례·89-A 사유 규약 정합).
 */
@Slf4j
@Service
@Transactional
public class RoleRevocationService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final LastSuperAdminGuard lastSuperAdminGuard;
    private final DemoAccountGuard demoAccountGuard;
    private final AuditRecorder auditRecorder;

    public RoleRevocationService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            LastSuperAdminGuard lastSuperAdminGuard,
            DemoAccountGuard demoAccountGuard,
            AuditRecorder auditRecorder) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.lastSuperAdminGuard = lastSuperAdminGuard;
        this.demoAccountGuard = demoAccountGuard;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 대상 회원의 역할을 회수한다(HARD delete). 성공 시 회수 이력을 같은 트랜잭션에서 감사 로그로 적재한다.
     *
     * @param callerUserId        요청을 수행한 인증 액터의 userId(SUPER_ADMIN 여부 검증 대상)
     * @param targetUserPublicId  회수 대상 회원의 public_id(usr_)
     * @param roleCode            회수할 역할 코드
     * @param reason              회수 사유(감사 after에 기록·컨트롤러가 @NotBlank 검증)
     * @param auditContext        감사 행위자 컨텍스트(운영자)
     * @throws SuperAdminRequiredException       caller가 SUPER_ADMIN이 아닌 경우(403)
     * @throws SelfRoleRevocationException       SUPER_ADMIN이 자기 자신의 SUPER_ADMIN 역할을 회수하려는 경우(403)
     * @throws LastSuperAdminRevocationException  마지막 SUPER_ADMIN 역할을 회수하려는 경우(409)
     * @throws com.zslab.mall.common.exception.DemoAccountProtectedException 데모 계정(403)
     * @throws RoleAssignmentNotFoundException   대상 미존재·역할 미보유·경합 선삭제로 삭제 행이 없는 경우(404)
     */
    public void revoke(Long callerUserId, String targetUserPublicId, RoleCode roleCode, String reason,
            AuditContext auditContext) {
        if (!userRoleRepository.existsByUserIdAndRole_Code(callerUserId, RoleCode.SUPER_ADMIN)) {
            log.warn("[RoleRevocation] SUPER_ADMIN 아님 차단(403) callerUserId={}", callerUserId);
            throw new SuperAdminRequiredException("SUPER_ADMIN만 권한을 회수할 수 있습니다.");
        }

        User target = userRepository.findByPublicId(targetUserPublicId)
                .orElseThrow(() -> new RoleAssignmentNotFoundException(
                        "회수 대상 역할 매핑을 찾을 수 없습니다: userPublicId=" + targetUserPublicId));
        Long targetUserId = target.getId();
        demoAccountGuard.requireNotProtected(target); // D-230: 역할 코드 무관(BUYER 회수도 데모 로그인을 막는다)

        if (roleCode == RoleCode.SUPER_ADMIN) {
            if (callerUserId.equals(targetUserId)) {
                log.warn("[RoleRevocation] SUPER_ADMIN 자기 회수 차단(403) callerUserId={}", callerUserId);
                throw new SelfRoleRevocationException("자기 자신의 SUPER_ADMIN 역할은 회수할 수 없습니다.");
            }
            // count 판정과 delete를 원자화(SUPER_ADMIN Role 행 잠금·활성 인원 집계 — 탈퇴 2경로와 공유·D-230).
            lastSuperAdminGuard.requireNotLastSuperAdmin(targetUserId);
        }

        int affected = userRoleRepository.deleteByUserIdAndRoleCode(targetUserId, roleCode);
        if (affected == 0) {
            throw new RoleAssignmentNotFoundException(
                    "회수 대상 역할 매핑을 찾을 수 없습니다: userPublicId=" + targetUserPublicId + " roleCode=" + roleCode);
        }

        auditRecorder.record(auditContext, AuditLogAction.DELETE, PolymorphicTargetType.USER, targetUserId,
                Map.of("role", roleCode.name()), Map.of("reason", reason));
        log.info("[RoleRevocation] 권한 회수 완료 targetUserPublicId={} roleCode={} byCallerUserId={}",
                targetUserPublicId, roleCode, callerUserId);
    }
}
