package com.zslab.mall.dashboard.repository;

import com.zslab.mall.order.enums.OrderItemStatus;
import java.time.LocalDateTime;

/** 셀러 최근 결제 품목 1행(Track 90-B-2). 주문 축은 번호·결제 시각뿐(구매자 id·주문 총액 없음). */
public interface SellerDashboardRecentOrderItemProjection {
    String getOrderItemPublicId();
    String getOrderNo();
    String getProductName();
    String getOptionLabel();
    Integer getQuantity();
    Long getTotalPrice();
    OrderItemStatus getItemStatus();
    LocalDateTime getPaidAt();
}
