package com.zslab.mall.cart.controller.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 장바구니 수량 변경 요청(Track 45·절대값 지정). 외부 대상키는 variantPublicId(var_·CHAR(30))다. userId는 인증 컨텍스트
 * 해소·요청 제외. 형식검증(@NotBlank·@Size·@Min)만 여기서 하고, 절대 수량 반영·대상 부재(404)는 Service가 담당한다(검증 경계).
 *
 * @param variantPublicId 대상 상품 변형 외부 식별자(var_)
 * @param quantity 변경할 절대 수량(≥1·CRT-2)
 */
public record CartItemQuantityUpdateRequest(
        @NotBlank @Size(max = 30) String variantPublicId, // SoT: product_variant.public_id CHAR(30)
        @NotNull @Min(1) Integer quantity) { // CRT-2: quantity ≥ 1
}
