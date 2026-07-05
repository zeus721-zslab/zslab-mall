package com.zslab.mall.product.exception;

/**
 * 지정한 상품 이미지가 존재하지 않거나 요청 판매자 소유가 아닐 때 발생한다(Track 59 BL-6). 소유권 미충족도 동일 404로
 * 은닉해 존재 여부를 노출하지 않는다(seller→product→image 2-hop 스코프·AddressNotFoundException 선례). 전역 예외 핸들러가
 * HTTP 404로 응답한다.
 */
public class ProductImageNotFoundException extends RuntimeException {

    public ProductImageNotFoundException(String message) {
        super(message);
    }
}
