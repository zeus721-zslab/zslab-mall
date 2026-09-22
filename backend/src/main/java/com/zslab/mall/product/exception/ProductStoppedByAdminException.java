package com.zslab.mall.product.exception;

/**
 * 관리자가 판매중지한 상품(saleStopSource=ADMIN)을 셀러가 재판매하려 할 때 발생한다(Track 96-5·D-206). 전역적으로 422
 * {@code PRODUCT_STOPPED_BY_ADMIN}으로 매핑된다({@link ProductInvalidStateException} 선례·RuntimeException + String message 단순 패턴).
 *
 * <p>{@link ProductInvalidStateException}(전이 위반 일반)과 코드를 분리하는 이유: FE가 "운영자에게 문의" 안내로 분기해야 하며, 전이 자체는
 * 합법(STOPPED → SALE)이고 주체만 다른 사건이다.
 */
public class ProductStoppedByAdminException extends RuntimeException {

    public ProductStoppedByAdminException(String message) {
        super(message);
    }
}
