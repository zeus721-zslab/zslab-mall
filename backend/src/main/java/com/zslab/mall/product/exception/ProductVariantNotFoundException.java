package com.zslab.mall.product.exception;

/**
 * 상품 변형 행을 찾을 수 없을 때 발생한다. Admin 재고 조정 등 variantPublicId 진입점이 미매칭 시 던진다
 * (Track 21 D-105 §5·{@code ClaimNotFoundException} 패턴 1:1).
 */
public class ProductVariantNotFoundException extends RuntimeException {

    public ProductVariantNotFoundException(String message) {
        super(message);
    }
}
