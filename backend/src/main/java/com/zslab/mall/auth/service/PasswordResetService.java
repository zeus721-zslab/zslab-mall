package com.zslab.mall.auth.service;

import com.zslab.mall.auth.entity.PasswordResetToken;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.exception.PasswordResetTokenInvalidException;
import com.zslab.mall.auth.repository.PasswordResetTokenRepository;
import com.zslab.mall.auth.repository.UserRoleRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.security.DemoAccountGuard;
import com.zslab.mall.notification.service.EmailMessage;
import com.zslab.mall.notification.service.NotificationService;
import com.zslab.mall.notification.template.NotificationMessages;
import com.zslab.mall.notification.template.NotificationTemplateCodes;
import com.zslab.mall.seller.repository.SellerUserRepository;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.policy.PasswordPolicy;
import com.zslab.mall.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매자 비밀번호 재설정(D-269). 메일 링크의 일회용 토큰으로 로그인 없이 비밀번호를 바꾼다.
 *
 * <ul>
 *   <li><b>활성</b>: 이메일 발송 구현체가 mock이 아닐 때만(D-209 {@code zslab.notification.email-sender}). 비활성이면 요청은 아무것도 하지 않는다.</li>
 *   <li><b>요청</b>: 가입 여부·역할과 무관하게 호출자는 같은 결과(즉시 반환)를 받는다. 대상 조회·토큰 발급·메일 발송은 이 서비스가 소유한
 *       단일 스레드 실행기에서 하므로 응답 시간도 발송 여부와 무관하다(이메일 열거 방지). 구매자 전용 계정만 발송하고, 새 발급은 같은 회원의
 *       미사용 토큰을 지운다.</li>
 *   <li><b>토큰</b>: SecureRandom 32바이트(base64url 43자)·DB에는 SHA-256 hex만·TTL {@value #TOKEN_TTL_MINUTES}분·1회 사용.</li>
 *   <li><b>확정</b>: 해시 조회 → 조건부 UPDATE로 사용 처리(동시 확정 1회) → 대상 재확인 → 기존 비밀번호 규칙 → 해시 교체 +
 *       {@link User#markCredentialsChanged}(기존 세션 무효화).</li>
 * </ul>
 *
 * <p>요청 제한은 앱이 아니라 gateway(nginx limit_req)가 맡는다(D-236 일원화). 토큰 원문·재설정 링크는 로그·notification_log에 남기지 않는다.
 */
@Slf4j
@Service
public class PasswordResetService implements DisposableBean {

    /** 재설정 토큰 유효 시간(분). */
    public static final long TOKEN_TTL_MINUTES = 30L;
    /** 프런트 재설정 화면 경로(링크 = base-url + 이 경로 + ?token=). FE pages/reset-password.vue와 같아야 한다. */
    static final String RESET_PAGE_PATH = "/reset-password?token=";
    static final String RESET_LINK_VARIABLE = "resetLink";
    private static final int TOKEN_BYTES = 32;
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String MOCK_EMAIL_SENDER = "mock";
    private static final String EVENT_NAME = "PasswordResetRequested";
    /** 관리자 역할 보유자는 구매자 겸직이어도 재설정 대상이 아니다(관리자 계정 탈취 경로 차단). */
    private static final Set<RoleCode> ADMIN_CODES = EnumSet.of(RoleCode.SUPER_ADMIN, RoleCode.ADMIN_OPERATOR);
    /** 대기 발급 상한. 넘치면 버리고 warn — 사용자는 다시 요청한다(요청 폭주는 gateway가 먼저 막는다). */
    private static final int QUEUE_CAPACITY = 1_000;
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;
    private static final String THREAD_NAME_PREFIX = "zslab-password-reset-";

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final SellerUserRepository sellerUserRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final NotificationService notificationService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final DemoAccountGuard demoAccountGuard;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;
    private final String frontendBaseUrl;
    private final SecureRandom secureRandom = new SecureRandom();
    /** 실행기를 빈이 아닌 필드로 둔다 — Executor 빈을 추가하면 Boot 기본 applicationTaskExecutor가 물러날 수 있다(ReviewSummaryHandler와 같은 이유). */
    private final ThreadPoolExecutor executor;

    public PasswordResetService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            SellerUserRepository sellerUserRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            NotificationService notificationService,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            DemoAccountGuard demoAccountGuard,
            PlatformTransactionManager transactionManager,
            @Value("${zslab.notification.email-sender:mock}") String emailSender,
            @Value("${zslab.frontend.base-url}") String frontendBaseUrl) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.sellerUserRepository = sellerUserRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.notificationService = notificationService;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.demoAccountGuard = demoAccountGuard;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.enabled = !MOCK_EMAIL_SENDER.equalsIgnoreCase(emailSender.trim());
        this.frontendBaseUrl = frontendBaseUrl;
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 실 메일 발송이 켜져 있을 때만 true — FE가 "비밀번호 찾기" 진입과 "준비 중" 안내를 고른다. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 재설정 요청. 결과를 알리지 않고 즉시 반환한다 — 발급·발송은 실행기에서 하고 실패도 로그로만 남는다.
     */
    public void requestReset(String email) {
        if (!enabled) {
            log.info("[PasswordReset] 메일 발송 비활성(mock) → 요청 무시");
            return;
        }
        Map<String, String> requestContext = MDC.getCopyOfContextMap();
        try {
            executor.execute(() -> issueInBackground(email, requestContext));
        } catch (RejectedExecutionException rejected) {
            // 큐 초과·종료 중: 응답을 다르게 만들지 않기 위해 흡수하고 기록만 남긴다(사용자는 다시 요청).
            log.warn("[PasswordReset] 발급 작업 거부 — 이번 요청을 버린다 queued={}", executor.getQueue().size(), rejected);
        }
    }

    /**
     * 재설정 확정. 토큰이 유효하면 비밀번호를 바꾸고 이 회원의 기존 세션을 무효화한다.
     *
     * @throws IllegalArgumentException 새 비밀번호가 정책 위반인 경우(400·토큰은 사용 처리하지 않는다)
     * @throws PasswordResetTokenInvalidException 토큰 없음·만료·이미 사용·대상 부적격(400)
     * @throws com.zslab.mall.common.exception.DemoAccountProtectedException 데모 계정(403)
     */
    @Transactional
    public void confirm(String rawToken, String newPassword) {
        passwordPolicy.validate(newPassword);
        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> invalid("NOT_FOUND", null));
        LocalDateTime now = LocalDateTime.now();
        if (passwordResetTokenRepository.markUsed(token.getId(), now) == 0) {
            throw invalid("USED_OR_EXPIRED", token.getUserId());
        }
        // 발급 뒤 30분 사이에 탈퇴·역할 변경이 있었을 수 있으므로 발급 조건을 다시 본다(실패 시 롤백으로 사용 처리도 되돌아간다).
        User user = userRepository.findById(token.getUserId())
                .filter(this::isResettableBuyer)
                .orElseThrow(() -> invalid("NOT_ELIGIBLE", token.getUserId()));
        demoAccountGuard.requireNotProtected(user);
        user.assignPasswordHash(passwordEncoder.encode(newPassword));
        user.markCredentialsChanged(now);
        user.clearPasswordChangeRequired();
        userRepository.save(user);
        log.info("[PasswordReset] 비밀번호 재설정 완료 userId={}", user.getId());
    }

    private void issueInBackground(String email, Map<String, String> requestContext) {
        if (requestContext != null) {
            MDC.setContextMap(requestContext);
        }
        try {
            // 토큰을 먼저 커밋한 뒤 메일을 보낸다 — 메일이 도착했을 때 링크가 이미 유효하고, 발송 대기(SMTP)가 토큰 행 잠금을 붙들지 않는다.
            IssuedToken issued = transactionTemplate.execute(status -> issue(email));
            if (issued != null) {
                transactionTemplate.executeWithoutResult(status -> sendResetMail(issued));
            }
        } catch (RuntimeException exception) {
            // 요청자는 이미 같은 응답을 받았다 — 예기치 않은 발급 실패(DB·발송 기록 등)는 운영 로그로만 남긴다.
            log.warn("[PasswordReset] 발급 실패 → 건너뜀 action=manual_review", exception);
        } finally {
            MDC.clear();
        }
    }

    /** 발급 대상이면 이전 미사용 토큰을 지우고 새 토큰 해시를 저장한다. 대상이 아니면 null(미발송). */
    private IssuedToken issue(String email) {
        User user = userRepository.findByEmail(email).orElse(null);
        // 데모 계정(D-230)도 "대상 아님"으로 조용히 넘긴다 — 누구나 반복 요청할 수 있으니 warn·스택을 쌓지 않는다(확정 단계는 403 가드 유지).
        if (user == null || !isResettableBuyer(user) || demoAccountGuard.isProtected(user)) {
            log.info("[PasswordReset] 발급 대상 아님 → 미발송 userId={}", user == null ? null : user.getId());
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        passwordResetTokenRepository.deleteUnusedByUserId(user.getId());
        String rawToken = newRawToken();
        passwordResetTokenRepository.save(PasswordResetToken.issue(user.getId(), hash(rawToken), now, now.plusMinutes(TOKEN_TTL_MINUTES)));
        return new IssuedToken(user.getId(), rawToken);
    }

    private void sendResetMail(IssuedToken issued) {
        notificationService.sendEmail(new EmailMessage(
                issued.userId(),
                NotificationMessages.PASSWORD_RESET_EMAIL_SUBJECT,
                NotificationMessages.PASSWORD_RESET_EMAIL_BODY.formatted(TOKEN_TTL_MINUTES),
                Map.of(RESET_LINK_VARIABLE, frontendBaseUrl + RESET_PAGE_PATH + issued.rawToken()),
                true,
                NotificationTemplateCodes.PASSWORD_RESET,
                PolymorphicTargetType.USER,
                issued.userId(),
                EVENT_NAME));
    }

    /** 커밋된 발급 결과(토큰 원문은 메일 본문으로만 나간다 — toString 마스킹). */
    private record IssuedToken(Long userId, String rawToken) {
        @Override
        public String toString() {
            return "IssuedToken[userId=" + userId + ", rawToken=****]";
        }
    }

    /**
     * 재설정 대상 = 비밀번호가 있는 활성 구매자 전용 계정. 셀러 구성원·관리자 역할 보유자는 BUYER를 겸해도 제외한다(범위: 구매자만).
     */
    private boolean isResettableBuyer(User user) {
        return user.getPasswordHash() != null
                && user.getWithdrawnAt() == null
                && userRoleRepository.existsByUserIdAndRole_Code(user.getId(), RoleCode.BUYER)
                && !userRoleRepository.existsByUserIdAndRole_CodeIn(user.getId(), ADMIN_CODES)
                && !sellerUserRepository.existsByUserId(user.getId());
    }

    private String newRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 토큰 원문 → SHA-256 hex. 원문이 고엔트로피(256비트)라 솔트·느린 해시가 필요 없고, 등치 조회가 가능해야 한다. */
    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance(HASH_ALGORITHM).digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 미지원 JVM", exception);
        }
    }

    private static PasswordResetTokenInvalidException invalid(String reasonCode, Long userId) {
        log.warn("[PasswordReset] 재설정 확정 거부 reason={} userId={}", reasonCode, userId);
        return new PasswordResetTokenInvalidException();
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
            log.warn("[PasswordReset] 종료 대기 초과 — 남은 발급 {}건을 버린다", executor.getQueue().size());
            executor.shutdownNow();
        }
    }
}
