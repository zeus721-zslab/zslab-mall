package com.zslab.mall.cart.controller.request;

import jakarta.validation.constraints.NotNull;

/**
 * 장바구니 단건 selected 토글 요청(Track 45). userId는 인증 컨텍스트 해소·요청 제외. selected=true 선택·false 해제이며,
 * 대상 부재(404)는 Service가 담당한다. 해제된 품목은 결제 대상(findByUserIdAndSelectedTrue)에서 제외된다.
 *
 * @param variantId 대상 상품 변형 식별자
 * @param selected 선택 여부(true 선택·false 해제)
 */
public record CartItemSelectRequest(
        @NotNull Long variantId,
        @NotNull Boolean selected) {
}
