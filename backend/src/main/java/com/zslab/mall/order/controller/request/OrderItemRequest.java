package com.zslab.mall.order.controller.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 주문 품목 요청(D-64·D-65). 식별자는 public_id(prd_/var_)·수량만 입력하며 단가·sellerId는 서버가 도출한다(가격 미입력·D-56 신뢰 차단).
 */
public record OrderItemRequest(
        @NotBlank String productId,
        @NotBlank String variantId,
        @Min(1) int quantity) {
}
