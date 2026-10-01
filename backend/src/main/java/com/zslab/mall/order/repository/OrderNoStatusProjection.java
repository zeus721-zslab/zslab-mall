package com.zslab.mall.order.repository;

import com.zslab.mall.order.enums.OrderStatus;

/** 주문번호·주문 상태 projection(D-253 답안 초안 근거 · 배송지 등 개인정보 컬럼 없음). */
public interface OrderNoStatusProjection {
    String getOrderNo();
    OrderStatus getStatus();
}
