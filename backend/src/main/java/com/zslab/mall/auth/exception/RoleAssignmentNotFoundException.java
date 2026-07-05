package com.zslab.mall.auth.exception;

/**
 * 회수 대상이 지정한 역할을 보유하지 않을 때 발생한다(Track 53 권한 회수·AUTH-4). 전역 예외 핸들러가 HTTP 404로 응답한다.
 *
 * <p>HARD delete가 0 row일 때 던진다 — User 미존재·역할 미보유·경합 선삭제를 하나의 404로 통합 은닉한다(대상 User 존재
 * 여부 비노출). "User 자체 미존재"를 뜻하는 {@link com.zslab.mall.user.exception.UserNotFoundException}과 의미가
 * 달라(역할 매핑 미존재) 별도 예외로 둔다.
 */
public class RoleAssignmentNotFoundException extends RuntimeException {

    public RoleAssignmentNotFoundException(String message) {
        super(message);
    }
}
