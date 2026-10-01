package com.zslab.mall.common.security;

import com.zslab.mall.common.exception.PublicDemoSessionRestrictedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 공개 관리자 데모 세션 제한(최종 점검 K1). 관리자 데모는 실제 SUPER_ADMIN 계정이라(FE-23) 업무 처리는 그대로 두고, 남의 계정을 가져가거나
 * 권한 구조를 바꾸는 조작(임시 비밀번호·탈퇴·역할 회수·운영 관리자 부여·셀러 구성원 추가·제외·역할 변경·입점 OWNER 지정)과 시더 API만 막는다.
 * 판정은 요청 토큰의 데모 표식이고, 비밀번호로 직접 로그인한 같은 계정의 세션은 막지 않는다. 각 서비스가 첫 줄에서 호출한다.
 */
@Slf4j
@Component
public class PublicDemoSessionGuard {

    static final String RESTRICTED_MESSAGE = "공개 데모 세션에서는 계정·권한 변경과 데모 데이터 생성을 할 수 없습니다.";

    /** @throws PublicDemoSessionRestrictedException 요청이 공개 관리자 데모 세션일 때(403) */
    public void requireNotPublicDemoSession() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isPublicDemo()) {
            log.warn("[DemoSession] 공개 데모 세션 차단(403) actorId={}", token.getPrincipal());
            throw new PublicDemoSessionRestrictedException(RESTRICTED_MESSAGE);
        }
    }
}
