package com.zslab.mall.user.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.entity.UserRole;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.repository.RoleRepository;
import com.zslab.mall.auth.repository.UserRoleRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.grade.entity.BuyerGrade;
import com.zslab.mall.grade.enums.BuyerGradeCode;
import com.zslab.mall.grade.repository.BuyerGradeRepository;
import com.zslab.mall.notification.enums.NotificationLogStatus;
import com.zslab.mall.notification.service.NotificationService;
import com.zslab.mall.notification.template.NotificationMessages;
import com.zslab.mall.notification.template.NotificationTemplateCodes;
import com.zslab.mall.user.entity.BuyerProfile;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.enums.GradeSource;
import com.zslab.mall.user.exception.EmailAlreadyExistsException;
import com.zslab.mall.user.exception.TemporaryPasswordDeliveryFailedException;
import com.zslab.mall.user.policy.PasswordPolicy;
import com.zslab.mall.user.repository.BuyerProfileRepository;
import com.zslab.mall.user.repository.UserRepository;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 주도 회원 계정 생성(Track 89-G). 셀러 구성원 등록에서 미가입자를 받을 때 호출되며, 계정 생성은 user 도메인 책임이라 seller 패키지가
 * 아닌 여기에 둔다 — 운영자 신규 계정(89-E 이월)이 같은 경로를 재사용한다.
 *
 * <p>셀프 가입({@link UserService#register})과 같은 결과물(User·BUYER role·BuyerProfile SILVER·AUTO)을 만들되 비밀번호만 임시 발급으로
 * 대체한다: 해시 저장 → 변경 강제 플래그 → 감사 CREATE USER → SMS 발송(같은 TX·Track 84 {@code resetPassword} 동형). 발송 FAILED면 예외로
 * 전체(계정·역할·프로필·호출자 트랜잭션)를 롤백한다. BUYER를 함께 부여하는 이유: 관리자 회원 목록·상세·탈퇴·임시 비밀번호 재발급이 전부 BUYER
 * 회원 기준이라, BUYER 없이 만들면 그 계정은 관리자 화면 어디서도 관리·재발급할 수 없다(D-189). 본인 비밀번호 변경은 role 무관
 * {@code PATCH /api/v1/users/me/password}로 구매자 마이페이지·셀러 설정 화면(FE-50) 양쪽에서 가능하다(D-201 Javadoc 교정).
 * 임시 비밀번호 평문은 SMS 원문과 반환값(호출자가 관리자 화면 1회 표시용 응답에만 싣는다·D-204)에만 쓰고 로그·감사·notification_log에는
 * 남기지 않는다.
 */
@Slf4j
@Service
@Transactional
public class AdminMemberProvisioningService {

    /** notification_log 저장본에서 임시 비밀번호 평문을 대신하는 문자열(본문 템플릿은 {@link NotificationMessages#TEMPORARY_PASSWORD_SMS}). */
    private static final String TEMPORARY_PASSWORD_MASK = "****";
    private static final String TEMPORARY_PASSWORD_EVENT = "TemporaryPassword";
    /** 감사 after에 "평문이 관리자 화면에 표시됐다"는 사실만 남기는 키(D-204·평문 아님). */
    private static final String AUDIT_DISPLAYED_TO_ACTOR = "displayedToActor";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final BuyerProfileRepository buyerProfileRepository;
    private final BuyerGradeRepository buyerGradeRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final NotificationService notificationService;
    private final AuditRecorder auditRecorder;

    public AdminMemberProvisioningService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            BuyerProfileRepository buyerProfileRepository,
            BuyerGradeRepository buyerGradeRepository,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            TemporaryPasswordGenerator temporaryPasswordGenerator,
            NotificationService notificationService,
            AuditRecorder auditRecorder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.buyerProfileRepository = buyerProfileRepository;
        this.buyerGradeRepository = buyerGradeRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.temporaryPasswordGenerator = temporaryPasswordGenerator;
        this.notificationService = notificationService;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 신규 회원 계정을 만들고 임시 비밀번호를 SMS로 발송한다.
     *
     * @param command      이메일·이름·휴대폰(형식 검증은 호출측 DTO)
     * @param auditContext 감사 행위자(운영자)
     * @return 생성된 회원(호출자가 역할 연결 등에 사용) + 화면 표시용 임시 비밀번호 평문(D-204)
     * @throws EmailAlreadyExistsException 이메일 중복(409·셀프 가입과 같은 검증·탈퇴 회원 이메일 포함)
     * @throws TemporaryPasswordDeliveryFailedException SMS 발송 실패(502·롤백)
     * @throws IllegalStateException BUYER Role 또는 SILVER BuyerGrade seed가 없는 경우(내부 오류·500)
     */
    public AdminMemberProvisionResult provision(AdminMemberProvisionCommand command, AuditContext auditContext) {
        String email = command.email().trim();
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException("이미 사용 중인 이메일");
        }
        String temporaryPassword = temporaryPasswordGenerator.generate();
        passwordPolicy.validate(temporaryPassword);

        User user = User.create(email, command.name().trim(), command.phone().trim());
        user.assignPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.requirePasswordChange();
        User saved = userRepository.save(user);

        Role buyerRole = roleRepository.findByCode(RoleCode.BUYER)
                .orElseThrow(() -> new IllegalStateException("BUYER Role seed 누락(V11 마이그레이션 확인 필요)."));
        userRoleRepository.save(UserRole.create(saved.getId(), buyerRole));
        BuyerGrade silver = buyerGradeRepository.findByCode(BuyerGradeCode.SILVER)
                .orElseThrow(() -> new IllegalStateException("SILVER BuyerGrade seed 누락(V15 마이그레이션 확인 필요)."));
        buyerProfileRepository.save(BuyerProfile.create(saved, silver.getId(), GradeSource.AUTO));

        // 생성 감사는 최소셋(publicId·role·변경 강제 플래그·화면 표시 사실). 이메일·이름·휴대폰은 AUD-2 민감정보 회피로 제외한다.
        auditRecorder.record(auditContext, AuditLogAction.CREATE, PolymorphicTargetType.USER, saved.getId(),
                Map.of(),
                Map.of("userPublicId", saved.getPublicId(), "role", RoleCode.BUYER.name(),
                        "passwordChangeRequired", true, AUDIT_DISPLAYED_TO_ACTOR, true));

        NotificationLogStatus status = notificationService.sendSensitiveSms(
                saved.getId(), saved.getPhone(), NotificationTemplateCodes.TEMPORARY_PASSWORD, "임시 비밀번호",
                String.format(NotificationMessages.TEMPORARY_PASSWORD_SMS, temporaryPassword),
                String.format(NotificationMessages.TEMPORARY_PASSWORD_SMS, TEMPORARY_PASSWORD_MASK), TEMPORARY_PASSWORD_EVENT);
        if (status != NotificationLogStatus.SENT) {
            throw new TemporaryPasswordDeliveryFailedException(
                    "임시 비밀번호 SMS 발송에 실패했습니다: userPublicId=" + saved.getPublicId());
        }
        log.info("[AdminMemberProvisioning] 계정 생성·임시 비밀번호 발급·화면 표시 userId={} publicId={} byActor={}",
                saved.getId(), saved.getPublicId(), auditContext.actorUserId());
        return new AdminMemberProvisionResult(saved, temporaryPassword);
    }
}
