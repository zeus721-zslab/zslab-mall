package com.zslab.mall.product.exception;

/**
 * 주문 이력(order_item)이 있는 상품을 삭제하려 할 때 발생한다(Track 76·409 PRODUCT_HAS_ORDER_HISTORY). order_item.product_id가
 * FK RESTRICT라 하드 삭제도 불가하며, 운영 대안은 판매중지(sale-status STOPPED)다 — 응답 detail이 이를 안내한다.
 */
public class ProductHasOrderHistoryException extends RuntimeException {

    public ProductHasOrderHistoryException(String message) {
        super(message);
    }
}
