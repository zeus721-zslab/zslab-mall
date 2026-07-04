package com.zslab.mall.cart.exception;

/**
 * 장바구니 결제 시 selected=true 품목이 하나도 없을 때 발생한다(Track 41 β·D3 빈 주문 선가드). CheckoutService/OrderService의
 * ORD-1(빈 주문 불가) 도달 전 {@code CartCheckoutService}가 던진다. 전역 예외 핸들러가 HTTP 422로 응답한다
 * (요청 자체는 well-formed이나 결제할 품목 부재 = 업무 전제 실패·클라 교정 가능[품목 선택 필요]).
 */
public class EmptyCartCheckoutException extends RuntimeException {

    public EmptyCartCheckoutException(String message) {
        super(message);
    }
}
