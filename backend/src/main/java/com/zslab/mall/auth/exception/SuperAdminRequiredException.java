package com.zslab.mall.auth.exception;

/**
 * SUPER_ADMIN 전용 작업을 SUPER_ADMIN이 아닌 액터가 시도할 때 발생한다(Track 38 운영 관리자 공급). 전역 예외 핸들러가
 * HTTP 403(FORBIDDEN)으로 응답한다.
 *
 * <p>JWT는 coarse ActorRole(ADMIN)만 담아 SUPER_ADMIN·ADMIN_OPERATOR를 구분하지 못하므로, SecurityConfig의
 * {@code hasRole("ADMIN")}(코어스 게이트)를 통과한 ADMIN 액터 중 SUPER_ADMIN이 아닌 경우를 서비스 진입 시 user_role
 * 실조회로 걸러 본 예외로 던진다(fail-closed).
 */
public class SuperAdminRequiredException extends RuntimeException {

    public SuperAdminRequiredException(String message) {
        super(message);
    }
}
