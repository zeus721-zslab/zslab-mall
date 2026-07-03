package com.zslab.mall.user.policy;

import org.springframework.stereotype.Component;

/**
 * 서비스 계층 비밀번호 정책 SSOT. DTO {@code @Size}와 동일 규칙을 비-HTTP 경로에도 보장하기 위한 최소 구조.
 *
 * <p>MVP 최소: 길이 규칙 1개(최소 8자). 복잡도 규칙은 필요 시 추가한다(현재 미도입·과잉개발 회피).
 */
@Component
public class PasswordPolicy {

    /** 최소 비밀번호 길이. SignupRequest의 {@code @Size(min=8)}과 동일 값. */
    private static final int MIN_LENGTH = 8;

    /**
     * 비밀번호가 정책을 만족하는지 검증한다.
     *
     * @throws IllegalArgumentException 최소 길이 미만인 경우(GlobalExceptionHandler 400 매핑)
     */
    public void validate(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("비밀번호는 최소 " + MIN_LENGTH + "자 이상이어야 합니다.");
        }
    }
}
