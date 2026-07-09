package com.zslab.mall.cart.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 장바구니 단건 selected 토글 요청(Track 45). 외부 대상키는 variantPublicId(var_·CHAR(30))다. userId는 인증 컨텍스트
 * 해소·요청 제외. selected=true 선택·false 해제이며, 대상 부재(404)는 Service가 담당한다. 해제된 품목은 결제
 * 대상(findByUserIdAndSelectedTrue)에서 제외된다.
 *
 * @param variantPublicId 대상 상품 변형 외부 식별자(var_)
 * @param selected 선택 여부(true 선택·false 해제)
 */
public record CartItemSelectRequest(
        @NotBlank @Size(max = 30) String variantPublicId, // SoT: product_variant.public_id CHAR(30)
        @NotNull Boolean selected) {
}
