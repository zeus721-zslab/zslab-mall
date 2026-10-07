package com.zslab.mall.auth.controller.response;

/**
 * 비밀번호 재설정 사용 가능 여부(D-269). 실 메일 발송이 꺼져 있으면 false — FE는 "준비 중" 안내를 보인다.
 *
 * @param enabled 재설정 메일을 보낼 수 있는지
 */
public record PasswordResetAvailabilityResponse(boolean enabled) {
}
