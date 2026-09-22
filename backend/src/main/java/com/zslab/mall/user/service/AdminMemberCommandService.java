package com.zslab.mall.user.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.repository.UserRoleRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.grade.entity.BuyerGrade;
import com.zslab.mall.grade.repository.BuyerGradeRepository;
import com.zslab.mall.notification.enums.NotificationLogStatus;
import com.zslab.mall.notification.service.NotificationService;
import com.zslab.mall.notification.template.NotificationMessages;
import com.zslab.mall.notification.template.NotificationTemplateCodes;
import com.zslab.mall.user.controller.request.AdminMemberGradeRequest;
import com.zslab.mall.user.controller.request.AdminMemberUpdateRequest;
import com.zslab.mall.user.controller.response.TemporaryPasswordResponse;
import com.zslab.mall.user.entity.BuyerProfile;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.exception.MemberAdminRoleAssignedException;
import com.zslab.mall.user.exception.MemberAlreadyWithdrawnException;
import com.zslab.mall.user.exception.MemberPhoneMissingException;
import com.zslab.mall.user.exception.TemporaryPasswordDeliveryFailedException;
import com.zslab.mall.user.policy.PasswordPolicy;
import com.zslab.mall.user.repository.BuyerProfileRepository;
import com.zslab.mall.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 회원 명령(Track 84): 정보 수정·탈퇴·임시 비밀번호 발급·수동 등급 변경. 대상 해소(404)는
 * {@link AdminMemberQueryService#requireBuyer}를 공유하고, 상태 변경은 같은 트랜잭션에서 감사 로그를 남긴다.
 * 임시 비밀번호 평문은 SMS 원문과 발급 응답(관리자 화면 1회 표시·D-204)에만 쓰고 로그·감사·notification_log에는 남기지 않는다.
 */
@Slf4j
@Service
@Transactional
public class AdminMemberCommandService {

    /** notification_log 저장본에서 임시 비밀번호 평문을 대신하는 문자열(본문 템플릿은 {@link NotificationMessages#TEMPORARY_PASSWORD_SMS}). */
    private static final String TEMPORARY_PASSWORD_MASK = "****";
    private static final String TEMPORARY_PASSWORD_EVENT = "TemporaryPassword";
    /** 감사 after에 "평문이 관리자 화면에 표시됐다"는 사실만 남기는 키(D-204·평문 아님). */
    private static final String AUDIT_DISPLAYED_TO_ACTOR = "displayedToActor";
    /** 임시 비밀번호 발급을 차단하는 관리자 역할(D-204·관리자 영역은 변경 강제가 없음). */
    private static final EnumSet<RoleCode> ADMIN_ROLE_CODES = EnumSet.of(RoleCode.SUPER_ADMIN, RoleCode.ADMIN_OPERATOR);
    /** 등급 고정 만료 시각의 당일 종료(DATETIME(6) 마이크로초 정밀도). */
    private static final LocalTime LOCK_END_OF_DAY = LocalTime.of(23, 59, 59, 999_999_000);

    private final AdminMemberQueryService adminMemberQueryService;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final BuyerProfileRepository buyerProfileRepository;
    private final BuyerGradeRepository buyerGradeRepository;
    private final MemberActivityChecker memberActivityChecker;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final NotificationService notificationService;
    private final AuditRecorder auditRecorder;

    public AdminMemberCommandService(AdminMemberQueryService adminMemberQueryService, UserRepository userRepository,
            UserRoleRepository userRoleRepository, BuyerProfileRepository buyerProfileRepository,
            BuyerGradeRepository buyerGradeRepository, MemberActivityChecker memberActivityChecker,
            PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy, TemporaryPasswordGenerator temporaryPasswordGenerator,
            NotificationService notificationService, AuditRecorder auditRecorder) {
        this.adminMemberQueryService = adminMemberQueryService;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.buyerProfileRepository = buyerProfileRepository;
        this.buyerGradeRepository = buyerGradeRepository;
        this.memberActivityChecker = memberActivityChecker;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.temporaryPasswordGenerator = temporaryPasswordGenerator;
        this.notificationService = notificationService;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 회원 정보 수정(name·phone). 감사 UPDATE USER(before/after).
     *
     * @throws com.zslab.mall.user.exception.UserNotFoundException 미존재·비BUYER(404)
     * @throws MemberAlreadyWithdrawnException 탈퇴 회원(409)
     */
    public void updateMember(String publicId, AdminMemberUpdateRequest request, AuditContext auditContext) {
        User user = requireActiveBuyer(publicId);
        Map<String, Object> before = profileFields(user);
        user.updateProfile(request.name(), request.phone());
        userRepository.save(user);
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.USER, user.getId(),
                before, profileFields(user));
        log.info("[AdminMember] 회원 정보 수정 userId={} byActor={}", user.getId(), auditContext.actorUserId());
    }

    /**
     * 관리자 탈퇴. 셀프 탈퇴와 같은 {@link MemberActivityChecker} 가드를 거친 뒤 withdrawn_at·자격증명 갱신 시각을 마킹해 기존 토큰을
     * 무효화한다. 감사는 DELETE USER(계정 종료·물리 삭제 아님)로 남긴다.
     *
     * @throws com.zslab.mall.user.exception.UserNotFoundException 미존재·비BUYER(404)
     * @throws MemberAlreadyWithdrawnException 이미 탈퇴(409)
     * @throws com.zslab.mall.user.exception.MemberActivityInProgressException 진행 중 주문·클레임(409)
     */
    public void withdrawMember(String publicId, AuditContext auditContext) {
        User user = requireActiveBuyer(publicId);
        memberActivityChecker.requireNoActivityInProgress(user.getId());
        LocalDateTime now = LocalDateTime.now();
        user.withdraw();
        user.markCredentialsChanged(now);
        userRepository.save(user);
        auditRecorder.record(auditContext, AuditLogAction.DELETE, PolymorphicTargetType.USER, user.getId(),
                Map.of(), Map.of("withdrawnAt", String.valueOf(user.getWithdrawnAt())));
        log.info("[AdminMember] 관리자 탈퇴 처리 userId={} byActor={}", user.getId(), auditContext.actorUserId());
    }

    /**
     * 임시 비밀번호 발급. 해시 저장 → 자격증명 갱신(기존 토큰 무효) → 변경 강제 플래그 → SMS 발송(같은 TX) → 평문을 응답으로 반환
     * (관리자 화면 1회 표시·D-204). 발송 FAILED면 예외로 전체를 롤백해 기존 비밀번호가 유지된다. 감사에는 필드명과 표시 사실
     * ({@value #AUDIT_DISPLAYED_TO_ACTOR})만 남는다(passwordHash는 {@code Masker}가 마스킹).
     *
     * @return 화면 표시용 평문(호출자는 응답 본문 외에 쓰지 않는다)
     * @throws com.zslab.mall.user.exception.UserNotFoundException 미존재·비BUYER(404)
     * @throws MemberAlreadyWithdrawnException 탈퇴 회원(409)
     * @throws MemberAdminRoleAssignedException 관리자 역할 보유 회원(422·권한 해제 후 재발급)
     * @throws MemberPhoneMissingException 연락처 없음(422)
     * @throws TemporaryPasswordDeliveryFailedException SMS 발송 실패(502·롤백)
     */
    public TemporaryPasswordResponse resetPassword(String publicId, AuditContext auditContext) {
        User user = requireActiveBuyer(publicId);
        // 관리자 영역은 변경 강제가 없어(adminAuth 플래그 미저장) 화면에 표시된 임시 비밀번호로 관리자 조작이 무기한 가능해진다 → 차단(D-204).
        if (userRoleRepository.existsByUserIdAndRole_CodeIn(user.getId(), ADMIN_ROLE_CODES)) {
            throw new MemberAdminRoleAssignedException(
                    "관리자 권한을 보유한 회원에게는 임시 비밀번호를 발급할 수 없습니다. 관리자 권한 해제 후 재발급하세요: publicId=" + publicId);
        }
        if (user.getPhone() == null || user.getPhone().isBlank()) {
            throw new MemberPhoneMissingException("연락처가 없어 임시 비밀번호를 발송할 수 없습니다: publicId=" + publicId);
        }
        String temporaryPassword = temporaryPasswordGenerator.generate();
        passwordPolicy.validate(temporaryPassword);

        Map<String, Object> before = credentialFields(user);
        user.assignPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.markCredentialsChanged(LocalDateTime.now());
        user.requirePasswordChange();
        userRepository.save(user);
        Map<String, Object> after = credentialFields(user);
        after.put(AUDIT_DISPLAYED_TO_ACTOR, true);
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.USER, user.getId(), before, after);

        NotificationLogStatus status = notificationService.sendSensitiveSms(
                user.getId(), user.getPhone(), NotificationTemplateCodes.TEMPORARY_PASSWORD, "임시 비밀번호",
                String.format(NotificationMessages.TEMPORARY_PASSWORD_SMS, temporaryPassword),
                String.format(NotificationMessages.TEMPORARY_PASSWORD_SMS, TEMPORARY_PASSWORD_MASK), TEMPORARY_PASSWORD_EVENT);
        if (status != NotificationLogStatus.SENT) {
            throw new TemporaryPasswordDeliveryFailedException("임시 비밀번호 SMS 발송에 실패했습니다: publicId=" + publicId);
        }
        log.info("[AdminMember] 임시 비밀번호 발급·화면 표시 userId={} byActor={}", user.getId(), auditContext.actorUserId());
        return new TemporaryPasswordResponse(temporaryPassword);
    }

    /**
     * 수동 등급 변경(MANUAL·lockedUntil 당일 종료까지 AUTO 재산정 skip). 감사 UPDATE USER(gradeId·gradeSource·gradeLockedUntil).
     *
     * @throws com.zslab.mall.user.exception.UserNotFoundException 미존재·비BUYER(404)
     * @throws MemberAlreadyWithdrawnException 탈퇴 회원(409)
     * @throws IllegalStateException BuyerProfile 또는 등급 마스터 seed가 없는 경우(불변식 위반·500)
     */
    public void changeGrade(String publicId, AdminMemberGradeRequest request, AuditContext auditContext) {
        User user = requireActiveBuyer(publicId);
        BuyerProfile profile = buyerProfileRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "BuyerProfile이 존재하지 않습니다(buyer 불변식 위반): userId=" + user.getId()));
        BuyerGrade grade = buyerGradeRepository.findByCode(request.gradeCode())
                .orElseThrow(() -> new IllegalStateException("BuyerGrade seed 누락(V15 마이그레이션 확인 필요): " + request.gradeCode()));

        Map<String, Object> before = gradeFields(profile);
        LocalDateTime now = LocalDateTime.now();
        // 당일 종료 = 23:59:59.999999 — DB DATETIME(6) 정밀도에 맞춘다(LocalTime.MAX의 나노 999_999_999는 반올림으로 익일 00:00:00이 될 수 있음).
        profile.assignManualGrade(grade.getId(), request.lockedUntil().atTime(LOCK_END_OF_DAY), now);
        buyerProfileRepository.save(profile);
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.USER, user.getId(),
                before, gradeFields(profile));
        log.info("[AdminMember] 수동 등급 변경 userId={} gradeId={} lockedUntil={} byActor={}",
                user.getId(), grade.getId(), profile.getGradeLockedUntil(), auditContext.actorUserId());
    }

    /**
     * @throws MemberAlreadyWithdrawnException 탈퇴 회원(409)
     */
    private User requireActiveBuyer(String publicId) {
        User user = adminMemberQueryService.requireBuyer(publicId);
        if (user.getWithdrawnAt() != null) {
            throw new MemberAlreadyWithdrawnException("이미 탈퇴한 회원입니다: publicId=" + publicId);
        }
        return user;
    }

    private static Map<String, Object> profileFields(User user) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", user.getName());
        fields.put("phone", user.getPhone());
        return fields;
    }

    private static Map<String, Object> credentialFields(User user) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("passwordHash", user.getPasswordHash());
        fields.put("passwordChangeRequired", user.isPasswordChangeRequired());
        return fields;
    }

    private static Map<String, Object> gradeFields(BuyerProfile profile) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("gradeId", profile.getGradeId());
        fields.put("gradeSource", profile.getGradeSource().name());
        fields.put("gradeLockedUntil", String.valueOf(profile.getGradeLockedUntil()));
        return fields;
    }
}
