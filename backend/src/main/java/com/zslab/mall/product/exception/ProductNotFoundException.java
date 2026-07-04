package com.zslab.mall.product.exception;

/**
 * 구매자 카탈로그에서 상품을 찾을 수 없을 때 발생한다(Track 44). 미존재·비노출(status≠SALE·판매자 비-ACTIVE·soft-delete)을
 * 구분 없이 404로 은닉해 상품 존재 여부 노출을 막는다({@code OrderNotFoundException}·§2 정보 노출 회피 패턴 정합).
 */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String message) {
        super(message);
    }
}
