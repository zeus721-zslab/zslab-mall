package com.zslab.mall.auth.exception;

/**
 * SUPER_ADMIN이 자기 자신의 역할을 회수하려 할 때 발생한다(Track 53 권한 회수·AUTH-4). 전역 예외 핸들러가 HTTP
 * 403(FORBIDDEN)으로 응답한다.
 *
 * <p>caller는 SUPER_ADMIN 게이트를 통과해 권한 자체는 충분하나, 자기 강등은 정책상 영구 금지된다(재시도 무의미). 이는
 * 리소스 상태 충돌(409)이 아니라 인가 규칙 위반이므로 {@link SuperAdminRequiredException} 선례와 동일하게 서비스 계층
 * 도메인 403(code=FORBIDDEN)으로 매핑한다.
 */
public class SelfRoleRevocationException extends RuntimeException {

    public SelfRoleRevocationException(String message) {
        super(message);
    }
}
