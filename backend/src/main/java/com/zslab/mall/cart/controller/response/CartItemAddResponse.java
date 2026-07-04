package com.zslab.mall.cart.controller.response;

/**
 * 장바구니 담기 응답(Track 40 provisioning). cart_item은 public_id 미부여(HARD·id-only)이며 내부 PK를 외부 노출하지
 * 않는 관례(D-124 정합)에 따라 식별자를 제외하고 담김 상태만 노출한다(userId·variantId·quantity·selected·과잉 금지).
 */
public record CartItemAddResponse(
        Long userId,
        Long variantId,
        Integer quantity,
        Boolean selected) {
}
