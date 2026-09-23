package com.zslab.mall.refund.repository;

/**
 * 품목별 기환불액 projection(Track 104-3a). refund → claim → order_item 경로로 품목에 귀속한 {@link RefundedCondition} 행의 amount 합이다.
 */
public interface OrderItemRefundedProjection {

    Long getOrderItemId();

    Long getRefundedAmount();
}
