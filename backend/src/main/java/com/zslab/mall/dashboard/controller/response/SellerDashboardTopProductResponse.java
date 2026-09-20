package com.zslab.mall.dashboard.controller.response;

/** 기간 매출 상위 자기 상품 1행(order_item.total_price 합·결제완료 기준). productName은 주문 시점 스냅샷(order_item.product_name). */
public record SellerDashboardTopProductResponse(
        String productPublicId,
        String productName,
        long revenue,
        long quantity) {
}
