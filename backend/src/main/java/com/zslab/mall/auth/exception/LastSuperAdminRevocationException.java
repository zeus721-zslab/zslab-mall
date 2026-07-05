package com.zslab.mall.auth.exception;

/**
 * 마지막 SUPER_ADMIN 역할을 회수하려 할 때 발생한다(Track 53 권한 회수·AUTH-4). 전역 예외 핸들러가 HTTP
 * 409(CONFLICT)로 응답한다.
 *
 * <p>SUPER_ADMIN이 0명이 되면 시스템 락아웃(운영 관리자 공급·권한 관리 불가)이 발생하므로 마지막 1명의 회수를 차단한다.
 * 시스템 전체 SUPER_ADMIN 집합의 현재 상태(마지막 1명)와 충돌하는 상태 의존적 위반이라 중복·동시성 충돌과 동일하게
 * 409로 매핑한다.
 */
public class LastSuperAdminRevocationException extends RuntimeException {

    public LastSuperAdminRevocationException(String message) {
        super(message);
    }
}
