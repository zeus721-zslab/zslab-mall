package com.zslab.mall.category.exception;

/**
 * 카테고리 삭제 차단 — 활성 상품이 1건이라도 연결된 카테고리를 soft-delete하려 할 때 발생한다(Track 89-C D-185).
 * soft-delete는 {@code fk_product_category ON DELETE RESTRICT}를 우회하므로 Service가 직접 가드한다. 전역 예외 핸들러가
 * HTTP 409(CONFLICT·{@code CATEGORY_HAS_PRODUCTS})로 응답하며 메시지에 연결 상품 수를 담는다.
 */
public class CategoryHasProductsException extends RuntimeException {

    public CategoryHasProductsException(String message) {
        super(message);
    }
}
