package com.zslab.mall.dashboard.controller.response;

/**
 * 처리 대기 건수 4종(D-180): 정산 PENDING · 클레임 REQUESTED · 배송 대기(order_item PAID) · 재고 임박(quantity_available 1~5·수동 품절 제외).
 * Track 96-2(D-203·C-01) 추가 2종: 상품 승인 대기(product PENDING·삭제 제외) · 셀러 승인 대기(seller PENDING·삭제 제외). 추가형 필드.
 * Track 96-4(D-205·C-02) 추가 1종: 클레임 처리 대기(관리자 후속 액션 5종 보유·목록 action=FOLLOWUP과 같은 Specification).
 * Track 99(D-210) 추가 1종: 장기 배송중(발송 OUTBOUND·SHIPPING·shipped_at이 {@code LongShippingThreshold.DAYS}일 이전).
 * Track 104-2(D-216) 추가 1종: 열린 불일치(reconciliation_issue status OPEN).
 */
public record DashboardPendingResponse(
        long settlementPending,
        long claimRequested,
        long deliveryReady,
        long lowStock,
        long productPending,
        long sellerPending,
        long claimFollowup,
        long longShipping,
        long reconciliationOpen) {
}
