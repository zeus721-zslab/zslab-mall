package com.zslab.mall.user.policy;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * 서비스 계층 비밀번호 정책 SSOT. DTO {@code @Size}와 동일 규칙을 비-HTTP 경로에도 보장하기 위한 최소 구조.
 *
 * <p>MVP 최소: 길이 규칙(최소 8자·UTF-8 최대 72바이트). 복잡도 규칙은 필요 시 추가한다(현재 미도입·과잉개발 회피).
 */
@Component
public class PasswordPolicy {

    /** 최소 비밀번호 길이. SignupRequest의 {@code @Size(min=8)}과 동일 값. */
    private static final int MIN_LENGTH = 8;

    /**
     * BCrypt 입력 상한(D-233·CVE-2025-22228). DTO {@code @Size(max=72)}는 문자 수라 한글 25자(75바이트)가 통과하고,
     * 그대로 encode하면 BCrypt가 IllegalArgumentException("password cannot be more than 72 bytes")을 던진다.
     */
    private static final int MAX_BYTES = 72;

    /**
     * 비밀번호가 정책을 만족하는지 검증한다.
     *
     * @throws IllegalArgumentException 최소 길이 미만이거나 UTF-8 72바이트 초과인 경우(GlobalExceptionHandler 400 매핑)
     */
    public void validate(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("비밀번호는 최소 " + MIN_LENGTH + "자 이상이어야 합니다.");
        }
        if (exceedsMaxBytes(rawPassword)) {
            throw new IllegalArgumentException("비밀번호는 72바이트 이하여야 합니다(영문 72자, 한글 약 24자).");
        }
    }

    /** encode 전에 BCrypt 상한을 넘는지 판정한다. HTTP 밖 경로(SuperAdminBootstrapRunner)도 같은 기준을 쓴다. */
    public static boolean exceedsMaxBytes(String rawPassword) {
        return rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }
}
