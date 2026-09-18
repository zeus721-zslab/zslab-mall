package com.zslab.mall.dashboard.controller.response;

/** 이번 달 매출 상위 셀러 1행(order_item.total_price 합·결제완료 기준). sellerName = seller.company_name. */
public record DashboardTopSellerResponse(
        String sellerPublicId,
        String sellerName,
        long revenue,
        long orderItemCount) {
}
