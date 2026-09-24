package com.zslab.mall.order.repository;

import com.zslab.mall.order.enums.OrderItemStatus;

/**
 * 품목 상태별 건수 집계 projection(Track 105-2d 구매자 주문 현황 요약). item_status로 GROUP BY한 품목 수를 반환한다.
 */
public interface ItemStatusCountProjection {

    OrderItemStatus getItemStatus();

    Long getItemCount();
}
