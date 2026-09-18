package com.zslab.mall.dashboard.controller.response;

/**
 * 처리 대기 건수 4종(D-180): 정산 PENDING · 클레임 REQUESTED · 배송 대기(order_item PAID) · 재고 임박(quantity_available 1~5·수동 품절 제외).
 */
public record DashboardPendingResponse(
        long settlementPending,
        long claimRequested,
        long deliveryReady,
        long lowStock) {
}
