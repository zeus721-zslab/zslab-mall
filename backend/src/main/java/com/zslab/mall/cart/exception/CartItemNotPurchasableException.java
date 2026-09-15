package com.zslab.mall.cart.exception;

/**
 * 장바구니 담기 대상 variant가 구매 불가(상품 판매중지·variant 비-SALE·수동품절·재고 0)일 때 발생한다(Track 71).
 * {@code CartService.addItem}이 던지며 전역 예외 핸들러가 HTTP 422로 응답한다(요청은 well-formed이나 업무 전제 실패·
 * 클라 교정 가능[다른 옵션 선택]·{@link EmptyCartCheckoutException} 선례 정합).
 */
public class CartItemNotPurchasableException extends RuntimeException {

    public CartItemNotPurchasableException(String message) {
        super(message);
    }
}
