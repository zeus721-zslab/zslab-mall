package com.zslab.mall.dashboard.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.order.enums.OrderStatus;
import java.time.LocalDateTime;

/** 최근 결제완료 주문 1행. buyerName은 관리자 주문 목록과 같이 user.name 원문(마스킹 없음·탈퇴 비식별화 시 null). */
public record DashboardRecentOrderResponse(
        String orderPublicId,
        String orderNo,
        String buyerName,
        long totalPrice,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime paidAt,
        OrderStatus status) {
}
