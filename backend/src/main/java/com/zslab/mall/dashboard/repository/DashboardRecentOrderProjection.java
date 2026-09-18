package com.zslab.mall.dashboard.repository;

import com.zslab.mall.order.enums.OrderStatus;
import java.time.LocalDateTime;

/** 최근 결제완료 주문 1행(Track 86). buyerId는 서비스에서 user.name 배치 enrich에 쓴다. */
public interface DashboardRecentOrderProjection {

    String getOrderPublicId();

    String getOrderNo();

    Long getBuyerId();

    Long getTotalPrice();

    LocalDateTime getPaidAt();

    OrderStatus getStatus();
}
