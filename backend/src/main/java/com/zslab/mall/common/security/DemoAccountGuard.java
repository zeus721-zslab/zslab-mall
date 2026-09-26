package com.zslab.mall.common.security;

import com.zslab.mall.common.exception.DemoAccountProtectedException;
import com.zslab.mall.user.entity.User;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 데모 계정 보호(D-230). 공개 데모 계정(관리자·구매자·셀러)의 로그인이 누구의 조작으로도 깨지지 않게, 보호 대상 계정에 대한
 * 비밀번호 변경·탈퇴·역할 해제·셀러 구성원 제외·소속 셀러 해지를 막는다. 판정은 이 한 곳에 두고 각 서비스가 대상 회원을 확정한 직후 호출한다.
 *
 * <p>보호 대상은 {@code zslab.demo.protected-emails}(쉼표 구분·앞뒤 공백 제거·대소문자 무시). DB 이메일 비교가 대소문자를 구분하지
 * 않으므로(utf8mb4_unicode_ci) 여기서도 소문자로 맞춘다. 비어 있으면 보호 없음(로컬 기본). 이름·연락처 등 로그인과 무관한 수정은 막지 않는다.
 */
@Slf4j
@Component
public class DemoAccountGuard {

    static final String DEMO_ACCOUNT_PROTECTED_MESSAGE = "데모 계정은 이 기능을 사용할 수 없습니다.";
    private static final String EMAIL_SEPARATOR = ",";

    private final Set<String> protectedEmails;

    /**
     * @param requireProtectedAccounts true면 보호 계정 0개일 때 기동을 실패시킨다(prod yml만 true — .env 누락으로 보호가 조용히 꺼지는
     *                                 fail-open 차단·D-230). local·test는 false(빈 값 = 보호 없음 유지).
     * @throws IllegalStateException requireProtectedAccounts=true인데 보호 계정이 0개(값은 메시지에 싣지 않는다)
     */
    public DemoAccountGuard(
            @Value("${zslab.demo.protected-emails:}") String protectedEmails,
            @Value("${zslab.demo.require-protected-accounts:false}") boolean requireProtectedAccounts) {
        this.protectedEmails = Arrays.stream(protectedEmails.split(EMAIL_SEPARATOR))
                .map(DemoAccountGuard::normalize)
                .filter(email -> !email.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        log.info("[DemoAccount] 보호 계정 {}개", this.protectedEmails.size());
        if (requireProtectedAccounts && this.protectedEmails.isEmpty()) {
            throw new IllegalStateException("zslab.demo.protected-emails 보호 계정이 0개입니다. 운영은 데모 계정 이메일"
                    + "(ADMIN_BOOTSTRAP_EMAIL·NUXT_BUYER_DEMO_EMAIL·NUXT_SELLER_DEMO_EMAIL)이 1개 이상 필요합니다.");
        }
    }

    /**
     * 대상 회원이 보호 대상 데모 계정이면 거부한다.
     *
     * @throws DemoAccountProtectedException 보호 대상 계정(403)
     */
    public void requireNotProtected(User target) {
        if (isProtected(target)) {
            log.warn("[DemoAccount] 데모 계정 보호 차단(403) userId={}", target.getId());
            throw new DemoAccountProtectedException(DEMO_ACCOUNT_PROTECTED_MESSAGE);
        }
    }

    /**
     * 구성원 중 보호 대상 데모 계정이 있으면 거부한다. 셀러 해지(TERMINATED·되돌릴 수 없음)가 구성원 전원의 셀러 로그인을 영구히
     * 막으므로 해지 경로에서 쓴다(일시 정지 등 되돌릴 수 있는 전이는 막지 않는다).
     *
     * @throws DemoAccountProtectedException 보호 대상 구성원 포함(403)
     */
    public void requireNoProtectedMember(Collection<User> members) {
        for (User member : members) {
            if (isProtected(member)) {
                log.warn("[DemoAccount] 데모 계정 소속 셀러 해지 차단(403) userId={}", member.getId());
                throw new DemoAccountProtectedException(DEMO_ACCOUNT_PROTECTED_MESSAGE);
            }
        }
    }

    private boolean isProtected(User user) {
        return user.getEmail() != null && protectedEmails.contains(normalize(user.getEmail()));
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
