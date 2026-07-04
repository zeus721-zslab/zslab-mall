package com.zslab.mall.product.exception;

/**
 * 상품 변형 옵션 조합 중복 — 동일 product 내 동일 (option1·option2·option3) 조합의 변형을 중복 등록하려 할 때
 * uk_product_variant_options 제약 위반(DataIntegrityViolationException)을 변환해 발생한다(Track 39·R5-1/M6·DB 제약을
 * 최종 방어선으로 삼는 사전 조회 금지 정책). 전역 예외 핸들러는 HTTP 409(CONFLICT)로 응답한다(매핑 배선은 P5·
 * SellerUserAlreadyExistsException 409 선례 정합).
 */
public class ProductVariantOptionConflictException extends RuntimeException {

    public ProductVariantOptionConflictException(String message) {
        super(message);
    }
}
