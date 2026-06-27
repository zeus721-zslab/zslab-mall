package com.zslab.mall.order.exception;

/**
 * 주문을 찾을 수 없거나 본인 주문이 아닐 때 발생한다(§2·D-42). 전역 예외 핸들러가 HTTP 404로 응답한다.
 *
 * <p>타인 주문(소유자 불일치)도 본 예외로 통일한다 — 존재 여부 노출을 회피하기 위함이다(403 미사용·§2).
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String message) {
        super(message);
    }
}
