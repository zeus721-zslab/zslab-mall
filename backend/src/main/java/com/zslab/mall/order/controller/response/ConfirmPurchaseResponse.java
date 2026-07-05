package com.zslab.mall.order.controller.response;

import com.zslab.mall.order.entity.OrderItem;

/**
 * 구매확정 응답(Track 47). 확정된 주문 품목의 public_id와 전이 결과 상태(CONFIRMED)를 반환한다. 내부 BIGINT 미노출.
 */
public record ConfirmPurchaseResponse(String orderItemId, String status) {

    public static ConfirmPurchaseResponse from(OrderItem orderItem) {
        return new ConfirmPurchaseResponse(orderItem.getPublicId(), orderItem.getItemStatus().name());
    }
}
