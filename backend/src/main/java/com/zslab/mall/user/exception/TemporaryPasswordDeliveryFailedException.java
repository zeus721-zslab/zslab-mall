package com.zslab.mall.user.exception;

/**
 * 임시 비밀번호 SMS 발송 실패(Track 84). 발급 트랜잭션 전체를 롤백하며 전역 예외 핸들러가 HTTP 502로 응답한다.
 */
public class TemporaryPasswordDeliveryFailedException extends RuntimeException {
    public TemporaryPasswordDeliveryFailedException(String message) {
        super(message);
    }
}
