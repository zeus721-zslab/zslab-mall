package com.zslab.mall.common.exception;

/**
 * 인증 정보(X-Buyer-Id 헤더)가 없을 때 발생한다(§2·D-39). 전역 예외 핸들러가 HTTP 401 {@code UNAUTHENTICATED}로 응답한다.
 */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException(String message) {
        super(message);
    }
}
