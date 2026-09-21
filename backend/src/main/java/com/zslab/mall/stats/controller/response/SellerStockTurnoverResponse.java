package com.zslab.mall.stats.controller.response;

/**
 * 재고 회전 1행(Track 90-E-3·SALE 상품·상품 단위 합산). inboundQuantity = 기간 inventory_history INBOUND 합, soldQuantity = 기간 결제 order_item 수량 합,
 * availableQuantity = 현재 가용 재고 합(현재 시점), depletionDays = ceil(availableQuantity × 기간 일수 ÷ soldQuantity)·판매 0이면 null(전역 NON_NULL이라
 * 필드 생략 → FE "판매 없음")·가용 0이면 0.
 */
public record SellerStockTurnoverResponse(
        String productKey,
        String productName,
        long inboundQuantity,
        long soldQuantity,
        long availableQuantity,
        Long depletionDays) {
}
