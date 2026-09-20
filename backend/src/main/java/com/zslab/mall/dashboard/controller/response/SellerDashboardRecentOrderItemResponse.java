package com.zslab.mall.dashboard.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.dashboard.repository.SellerDashboardRecentOrderItemProjection;
import com.zslab.mall.order.enums.OrderItemStatus;
import java.time.LocalDateTime;

/**
 * 최근 결제 자기 품목 1행(Track 90-B-2). {@code SellerOrderItemSummaryResponse}보다 축약된 전용 DTO다 — 목록 DTO를 재사용하면 수령인·배송
 * enrich 4쿼리가 붙어 대시보드엔 과하다. 상세 링크는 {@code /seller/order-items/{orderItemId}}. 구매자·주문 총액 필드는 없다.
 */
public record SellerDashboardRecentOrderItemResponse(
        String orderItemId,
        String orderNo,
        String productName,
        String optionLabel,
        int quantity,
        long totalPrice,
        OrderItemStatus itemStatus,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime paidAt) {

    public static SellerDashboardRecentOrderItemResponse from(SellerDashboardRecentOrderItemProjection row) {
        return new SellerDashboardRecentOrderItemResponse(row.getOrderItemPublicId(), row.getOrderNo(), row.getProductName(),
                row.getOptionLabel(), row.getQuantity(), row.getTotalPrice(), row.getItemStatus(), row.getPaidAt());
    }
}
