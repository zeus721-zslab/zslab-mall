package com.zslab.mall.order.controller.request;

import com.zslab.mall.cart.entity.CartItem;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 주문 품목 요청(D-64·D-65). 식별자는 public_id(prd_/var_)·수량만 입력하며 단가·sellerId는 서버가 도출한다(가격 미입력·D-56 신뢰 차단).
 * 수량 상한은 장바구니 품목 상한과 같다(바로구매도 장바구니 경로와 같은 999).
 */
public record OrderItemRequest(
        @NotBlank String productId,
        @NotBlank String variantId,
        @Min(1) @Max(CartItem.MAX_QUANTITY) int quantity) {
}
