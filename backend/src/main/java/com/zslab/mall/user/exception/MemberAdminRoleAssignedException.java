package com.zslab.mall.user.exception;

/**
 * 관리자 역할(SUPER_ADMIN·ADMIN_OPERATOR)을 보유한 회원에게 임시 비밀번호를 발급하려는 시도(D-204). 관리자 영역은 변경 강제가 없어
 * 화면에 표시된 임시 비밀번호로 관리자 조작이 무기한 가능해지므로 차단한다(권한 해제 후 재발급). 전역 예외 핸들러가 HTTP 422로 응답한다.
 */
public class MemberAdminRoleAssignedException extends RuntimeException {
    public MemberAdminRoleAssignedException(String message) {
        super(message);
    }
}
