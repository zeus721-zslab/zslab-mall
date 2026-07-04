package com.zslab.mall.auth.exception;

/**
 * user_role 복합 UNIQUE(uk_user_role(user_id, role_id)·V1) 위반 — 이미 ADMIN_OPERATOR 역할을 보유한 회원에게 같은
 * 역할을 다시 부여하려 할 때 발생한다(Track 38 운영 관리자 공급). 전역 예외 핸들러가 HTTP 409(CONFLICT)로 응답한다.
 *
 * <p>중복 가드는 DB 제약을 최종 방어선으로 삼는다(Track 37 provisioning 패턴 동일): pre-check 대신
 * {@code saveAndFlush}가 던지는 {@code DataIntegrityViolationException}을 본 예외로 변환한다.
 */
public class AdminOperatorAlreadyExistsException extends RuntimeException {

    public AdminOperatorAlreadyExistsException(String message) {
        super(message);
    }
}
