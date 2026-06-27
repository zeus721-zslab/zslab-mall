package com.zslab.mall.order.exception;

/**
 * 재결제 차단 사유(D-51·D-60). ProblemDetail.detail 코드로 노출한다(§6). SHIPPING_UNAVAILABLE은 본 트랙 보류(D-62a).
 */
public enum OrderNotPayableReason {
    PRODUCT_NOT_ON_SALE,
    OUT_OF_STOCK
}
