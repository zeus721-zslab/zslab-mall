package com.zslab.mall.order.controller.response;

import java.util.List;

/**
 * seller 단위 그룹(§11·D-45). 단일 판매자 주문도 배열 길이 1로 동일 구조. subtotal=그룹 내 품목 total_price 합(정산 단위·null 금지).
 */
public record SellerGroupResponse(
        String sellerId,
        String companyName,
        List<OrderItemResponse> items,
        long subtotal) {
}
