package com.zslab.mall.stats.controller.response;

/**
 * 판매 상위·하위 1행(Track 90-E-3). productKey는 상품 public_id(soft-delete로 미존재면 null), productName은 order_item.product_name 스냅샷(주문 시점),
 * revenue = 자기 품목 total_price 합·orderCount = DISTINCT 주문 수·quantity = 수량 합(매출 분해 PRODUCT 축과 같은 정의).
 */
public record SellerProductRankResponse(
        String productKey,
        String productName,
        long revenue,
        long orderCount,
        long quantity) {
}
