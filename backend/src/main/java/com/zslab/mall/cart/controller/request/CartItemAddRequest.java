package com.zslab.mall.cart.controller.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 장바구니 담기 요청(Track 40 provisioning·buyer 주도). userId는 인증 컨텍스트에서 해소하므로 요청에 두지 않는다.
 * 형식 검증(@NotNull·@Min)만 여기서 하고, 수량 누적·variant 존재검증은 Service가 담당한다(검증 경계·CLAUDE.md).
 */
public record CartItemAddRequest(
        @NotNull Long variantId,
        @NotNull @Min(1) Integer quantity) { // CRT-2: quantity ≥ 1
}
