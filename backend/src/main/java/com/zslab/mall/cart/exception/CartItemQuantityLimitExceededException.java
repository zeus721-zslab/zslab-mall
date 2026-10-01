package com.zslab.mall.cart.exception;

/**
 * 동일 variant 재담기 합산 수량이 품목당 상한({@link com.zslab.mall.cart.entity.CartItem#MAX_QUANTITY})을 넘을 때 발생한다(최종 점검 K3).
 * {@code CartItem.addQuantity}가 던지며 전역 예외 핸들러가 HTTP 422로 응답한다(요청은 well-formed이나 담긴 수량과 합쳐 업무 전제 실패·
 * {@link CartItemNotPurchasableException} 선례 정합).
 */
public class CartItemQuantityLimitExceededException extends RuntimeException {

    public CartItemQuantityLimitExceededException(String message) {
        super(message);
    }
}
