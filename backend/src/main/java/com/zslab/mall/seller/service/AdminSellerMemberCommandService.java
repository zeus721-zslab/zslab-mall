package com.zslab.mall.seller.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.repository.RoleRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.seller.controller.request.AdminSellerMemberAddRequest;
import com.zslab.mall.seller.controller.response.AdminSellerDetailResponse;
import com.zslab.mall.seller.controller.response.AdminSellerMemberAddResponse;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.entity.SellerUser;
import com.zslab.mall.seller.exception.SellerLastOwnerException;
import com.zslab.mall.seller.exception.SellerMemberInvalidStateException;
import com.zslab.mall.seller.exception.SellerMemberNotFoundException;
import com.zslab.mall.seller.exception.SellerNotFoundException;
import com.zslab.mall.seller.exception.SellerUserAlreadyExistsException;
import com.zslab.mall.seller.repository.SellerRepository;
import com.zslab.mall.seller.repository.SellerUserRepository;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.exception.MemberAlreadyWithdrawnException;
import com.zslab.mall.user.exception.UserNotFoundException;
import com.zslab.mall.user.repository.UserRepository;
import com.zslab.mall.user.service.AdminMemberProvisionCommand;
import com.zslab.mall.user.service.AdminMemberProvisionResult;
import com.zslab.mall.user.service.AdminMemberProvisioningService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 셀러 구성원 명령(Track 89-G·D-189): 추가(기존 회원·신규 계정)·제거·역할 변경. 세 명령 모두 {@code findByPublicIdForUpdate}
 * (셀러 행 비관적 락)로 같은 셀러에 대한 동시 명령을 직렬화한다 — "마지막 활성 OWNER" 가드가 읽고-판정하는 구조라 락 없이는 두 제거가
 * 동시에 통과할 수 있다.
 *
 * <p><b>구성원 = 셀러 판정의 유일 근거</b>: 로그인({@code DbRoleAuthorization} seller_user 존재)·매 요청 액터 해소({@code
 * HeaderSellerActorResolver} user→seller 단건)가 이 테이블만 본다. 따라서 추가 = 즉시 셀러 로그인·API 가능, 제거 = 다음 요청부터 즉시 401
 * (리졸버가 매 요청 DB를 읽음). 별도 토큰 무효화가 필요 없고, {@code User.markCredentialsChanged}는 user 축이라 호출하면 BUYER 겸직 세션까지
 * 끊기므로 <b>호출하지 않는다</b>(D-189 §1-A 1).
 *
 * <p><b>마지막 OWNER 가드 판정 기준</b>: 대상이 <i>활성</i> OWNER(user 존재·withdrawn_at NULL)이고 그 외 활성 OWNER가 0명이면 제거·강등 409.
 * 탈퇴·삭제된 OWNER 행은 기능하는 대표가 아니므로 세지 않고, 그 행 자체의 제거도 막지 않는다(셀러 2처럼 유일 구성원이 탈퇴자인 경우 정리 가능).
 * 구성원 0명 셀러는 정상 운영 중이므로(셀러 1) "구성원 0 금지"·"OWNER 필수"는 가드가 아니다.
 */
@Slf4j
@Service
@Transactional
public class AdminSellerMemberCommandService {

    private final SellerRepository sellerRepository;
    private final SellerUserRepository sellerUserRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AdminMemberProvisioningService adminMemberProvisioningService;
    private final AuditRecorder auditRecorder;

    public AdminSellerMemberCommandService(
            SellerRepository sellerRepository,
            SellerUserRepository sellerUserRepository,
            UserRepository userRepository,
            RoleRepository roleRepository,
            AdminMemberProvisioningService adminMemberProvisioningService,
            AuditRecorder auditRecorder) {
        this.sellerRepository = sellerRepository;
        this.sellerUserRepository = sellerUserRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.adminMemberProvisioningService = adminMemberProvisioningService;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 구성원 추가. 기존 회원({@code userPublicId})은 활성 회원만, 신규({@code newUser})는 user 도메인이 계정을 만든 뒤(BUYER 겸직·임시 비밀번호
     * SMS·같은 TX) 연결한다. 사유 없음·감사 CREATE SELLER(after {userId, roleCode, newUserCreated}).
     *
     * @throws SellerNotFoundException 셀러 미존재(404)
     * @throws UserNotFoundException userPublicId 미존재·soft-delete(404)
     * @throws MemberAlreadyWithdrawnException 탈퇴 회원(409·로그인 불가 계정은 붙이는 의미가 없음)
     * @throws SellerUserAlreadyExistsException 이미 이 셀러 또는 타 셀러 소속(409·V12 user_id 단독 UNIQUE)
     * @throws com.zslab.mall.user.exception.EmailAlreadyExistsException 신규 이메일 중복(409)
     * @throws com.zslab.mall.user.exception.TemporaryPasswordDeliveryFailedException 신규 SMS 발송 실패(502·전체 롤백)
     */
    public AdminSellerMemberAddResponse add(String sellerPublicId, AdminSellerMemberAddRequest request,
            AuditContext auditContext) {
        Seller seller = requireSellerForUpdate(sellerPublicId);
        Role role = requireRole(RoleCode.valueOf(request.role()));
        boolean newUserCreated = request.newUser() != null;
        // 신규 계정이면 임시 비밀번호 평문을 응답(관리자 화면 1회 표시·D-204)에만 싣는다. 기존 회원 연결은 null.
        AdminMemberProvisionResult provisioned = newUserCreated
                ? adminMemberProvisioningService.provision(new AdminMemberProvisionCommand(
                        request.newUser().email(), request.newUser().name(), request.newUser().phone()), auditContext)
                : null;
        User user = newUserCreated ? provisioned.user() : requireActiveUser(request.userPublicId());
        assertNotMember(seller, user);

        // 선검사 통과 후 레이스는 uk_seller_user_user_id 위반으로 DataIntegrityViolation → GlobalExceptionHandler 기본 경로(입점 catch 미복제).
        SellerUser sellerUser = sellerUserRepository.saveAndFlush(SellerUser.create(seller, user.getId(), role.getId()));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("userId", user.getId());
        after.put("roleCode", role.getCode().name());
        after.put("newUserCreated", newUserCreated);
        auditRecorder.record(auditContext, AuditLogAction.CREATE, PolymorphicTargetType.SELLER, seller.getId(), Map.of(), after);
        log.info("[AdminSellerMember] 추가 sellerPublicId={} userId={} role={} newUser={} byActor={}",
                sellerPublicId, user.getId(), role.getCode(), newUserCreated, auditContext.actorUserId());
        return AdminSellerMemberAddResponse.of(toMember(sellerUser, user, role.getCode()),
                newUserCreated ? provisioned.temporaryPassword() : null);
    }

    /**
     * 구성원 제거(hard-delete·seller_user에 deleted_at 없음·FK 피참조 0). 사유 필수·감사 DELETE SELLER. 제거 즉시 그 계정의 셀러 API는
     * 401이 된다(리졸버 매 요청 조회). 토큰 무효화·{@code credentials_changed_at} 갱신은 하지 않는다(클래스 Javadoc).
     *
     * @throws SellerNotFoundException 셀러 미존재(404)
     * @throws SellerMemberNotFoundException 이 셀러의 구성원이 아님(404·회원 미존재·타 셀러 소속 모두 같은 코드로 은닉)
     * @throws SellerLastOwnerException 마지막 활성 OWNER(409)
     */
    public void remove(String sellerPublicId, String userPublicId, String reason, AuditContext auditContext) {
        Seller seller = requireSellerForUpdate(sellerPublicId);
        User user = requireMemberUser(userPublicId);
        SellerUser sellerUser = requireMember(seller, user);
        Role role = requireRoleById(sellerUser.getRoleId());
        assertNotLastActiveOwner(seller, sellerUser, user, role, "제거");

        sellerUserRepository.delete(sellerUser);
        sellerUserRepository.flush();

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("userId", user.getId());
        before.put("roleCode", role.getCode().name());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reason", reason.trim());
        auditRecorder.record(auditContext, AuditLogAction.DELETE, PolymorphicTargetType.SELLER, seller.getId(), before, after);
        log.info("[AdminSellerMember] 제거 sellerPublicId={} userId={} role={} byActor={}",
                sellerPublicId, user.getId(), role.getCode(), auditContext.actorUserId());
    }

    /**
     * 역할 변경. 같은 역할 재요청 422(같은 상태 재요청 관습)·마지막 활성 OWNER 강등 409·사유 필수·감사 UPDATE SELLER.
     *
     * @throws SellerNotFoundException 셀러 미존재(404)
     * @throws SellerMemberNotFoundException 이 셀러의 구성원이 아님(404·회원 미존재·타 셀러 소속 모두 같은 코드로 은닉)
     * @throws SellerMemberInvalidStateException 이미 같은 역할(422)
     * @throws SellerLastOwnerException 마지막 활성 OWNER 강등(409)
     */
    public void changeRole(String sellerPublicId, String userPublicId, RoleCode target, String reason,
            AuditContext auditContext) {
        Seller seller = requireSellerForUpdate(sellerPublicId);
        User user = requireMemberUser(userPublicId);
        SellerUser sellerUser = requireMember(seller, user);
        Role current = requireRoleById(sellerUser.getRoleId());
        if (current.getCode() == target) {
            throw new SellerMemberInvalidStateException(
                    "이미 " + target + " 역할입니다: sellerPublicId=" + sellerPublicId + " userPublicId=" + userPublicId);
        }
        assertNotLastActiveOwner(seller, sellerUser, user, current, "강등");
        Role next = requireRole(target);
        sellerUser.changeRole(next.getId());
        sellerUserRepository.flush();

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("userId", user.getId());
        before.put("roleCode", current.getCode().name());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("userId", user.getId());
        after.put("roleCode", next.getCode().name());
        after.put("reason", reason.trim());
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.SELLER, seller.getId(), before, after);
        log.info("[AdminSellerMember] 역할 변경 sellerPublicId={} userId={} {}→{} byActor={}",
                sellerPublicId, user.getId(), current.getCode(), next.getCode(), auditContext.actorUserId());
    }

    // ---------- 가드 ----------

    /**
     * 마지막 활성 OWNER 가드. 대상이 활성 OWNER가 아니면(탈퇴 회원·OWNER 아님) 통과. 활성 = user 행 존재(soft-delete 제외·findByIdIn
     * {@code @SQLRestriction}) ∧ withdrawn_at NULL. 역할은 role_id로 판정한다.
     */
    private void assertNotLastActiveOwner(Seller seller, SellerUser target, User targetUser, Role targetRole, String action) {
        if (targetRole.getCode() != RoleCode.SELLER_OWNER || targetUser.getWithdrawnAt() != null) {
            return;
        }
        List<SellerUser> members = sellerUserRepository.findBySellerId(seller.getId());
        List<Long> otherOwnerUserIds = members.stream()
                .filter(member -> !member.getId().equals(target.getId()))
                .filter(member -> member.getRoleId().equals(target.getRoleId()))
                .map(SellerUser::getUserId)
                .toList();
        boolean anotherActiveOwner = !otherOwnerUserIds.isEmpty() && userRepository.findByIdIn(otherOwnerUserIds).stream()
                .anyMatch(user -> user.getWithdrawnAt() == null);
        if (!anotherActiveOwner) {
            throw new SellerLastOwnerException("마지막 활성 SELLER_OWNER는 " + action + "할 수 없습니다(다른 OWNER를 먼저 추가): "
                    + "sellerPublicId=" + seller.getPublicId() + " userPublicId=" + targetUser.getPublicId());
        }
    }

    private void assertNotMember(Seller seller, User user) {
        Optional<SellerUser> existing = sellerUserRepository.findByUserId(user.getId());
        if (existing.isPresent()) {
            boolean sameSeller = existing.get().getSeller().getId().equals(seller.getId());
            throw new SellerUserAlreadyExistsException(sameSeller
                    ? "이미 이 판매자의 구성원입니다: userPublicId=" + user.getPublicId()
                    : "이미 다른 판매자에 소속된 사용자입니다: userPublicId=" + user.getPublicId());
        }
    }

    // ---------- 해소 ----------

    private Seller requireSellerForUpdate(String sellerPublicId) {
        return sellerRepository.findByPublicIdForUpdate(sellerPublicId)
                .orElseThrow(() -> new SellerNotFoundException("셀러를 찾을 수 없습니다: " + sellerPublicId));
    }

    private User requireUser(String userPublicId) {
        return userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new UserNotFoundException("회원을 찾을 수 없습니다: userPublicId=" + userPublicId));
    }

    /**
     * 제거·역할 변경 대상 해소(R1 외부 검토 지적 1). 회원 미존재도 {@link SellerMemberNotFoundException}으로 던져 타 셀러 소속과 같은
     * 404 코드가 되게 한다 — 경로 하위 대상의 "미존재"와 "범위 밖"을 같은 코드로 은닉하는 기존 관리자 API 정책(Track 53 역할 회수
     * ROLE_ASSIGNMENT_NOT_FOUND·Track 84 requireBuyer USER_NOT_FOUND·89-F 계좌 SELLER_BANK_ACCOUNT_NOT_FOUND)과 정합. 추가는 대상 탐색
     * API라 {@link #requireActiveUser}(USER_NOT_FOUND 404·409 구분·89-E 부여 동형)를 그대로 쓴다.
     */
    private User requireMemberUser(String userPublicId) {
        return userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new SellerMemberNotFoundException("셀러 구성원을 찾을 수 없습니다: userPublicId=" + userPublicId));
    }

    /** @throws MemberAlreadyWithdrawnException 탈퇴 회원(409) */
    private User requireActiveUser(String userPublicId) {
        User user = requireUser(userPublicId);
        if (user.getWithdrawnAt() != null) {
            throw new MemberAlreadyWithdrawnException("탈퇴한 회원은 구성원으로 추가할 수 없습니다: userPublicId=" + userPublicId);
        }
        return user;
    }

    private SellerUser requireMember(Seller seller, User user) {
        return sellerUserRepository.findBySellerIdAndUserId(seller.getId(), user.getId())
                .orElseThrow(() -> new SellerMemberNotFoundException(
                        "셀러 구성원을 찾을 수 없습니다: sellerPublicId=" + seller.getPublicId() + " userPublicId=" + user.getPublicId()));
    }

    private Role requireRole(RoleCode code) {
        return roleRepository.findByCode(code)
                .orElseThrow(() -> new IllegalStateException(code + " Role seed 누락(V11 마이그레이션 확인 필요)."));
    }

    private Role requireRoleById(Long roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalStateException("seller_user.role_id가 참조하는 Role이 없습니다(FK 무결성 위반): " + roleId));
    }

    private static AdminSellerDetailResponse.Member toMember(SellerUser sellerUser, User user, RoleCode roleCode) {
        return new AdminSellerDetailResponse.Member(
                user.getPublicId(), user.getEmail(), user.getName(), roleCode, user.getWithdrawnAt(), sellerUser.getCreatedAt());
    }
}
