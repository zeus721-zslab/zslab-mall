package com.zslab.mall.dashboard.controller.response;

/**
 * 처리 대기 건수 4종(기간 무관): 배송 대기(자기 품목 PAID) · 클레임 REQUESTED · 재고 임박(자기 상품 가용 1~5·수동 품절 제외) ·
 * 정산 예정(PENDING 건수 — 금액 아님·D-191).
 * Track 99(D-210) 추가 1종: 장기 배송중(자기 품목의 발송 OUTBOUND·SHIPPING·shipped_at이 {@code LongShippingThreshold.DAYS}일 이전).
 */
public record SellerDashboardPendingResponse(
        long deliveryReady,
        long claimRequested,
        long lowStock,
        long settlementPending,
        long longShipping) {
}
