package com.zslab.mall.cart.controller.response;

/**
 * 장바구니 담기 응답(Track 40 provisioning). cart_item 내부 PK는 외부 노출하지 않는 관례(D-124 정합)에 따라 담김 상태만
 * 노출하며, 대상 식별자는 외부 키 variantPublicId(var_)로 반환한다(userId·variantPublicId·quantity·selected·과잉 금지).
 */
public record CartItemAddResponse(
        Long userId,
        String variantPublicId,
        Integer quantity,
        Boolean selected) {
}
