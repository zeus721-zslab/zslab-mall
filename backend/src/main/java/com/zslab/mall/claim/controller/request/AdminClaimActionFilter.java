package com.zslab.mall.claim.controller.request;

/**
 * 관리자 클레임 목록 "필요 액션" 필터(Track 96-4 D-205·C-02). 값은 {@code AdminClaimQueryService.availableActions}의 후속 처리 액션
 * 5종과 그 합집합 {@link #FOLLOWUP}이다. APPROVE·REJECT는 기존 {@code status=REQUESTED} 필터가 담당하므로 제외하며, 허용 외 값은
 * Spring 변환 실패 400({@link AdminClaimSort} 선례).
 */
public enum AdminClaimActionFilter {
    /** 후속 처리 전체 = 아래 5종 합집합(대시보드 "클레임 처리 대기" 타일과 같은 조건). */
    FOLLOWUP,
    CONFIRM_PICKUP,
    INSPECT,
    REGISTER_EXCHANGE_SHIPMENT,
    MARK_EXCHANGE_DELIVERED,
    INITIATE_REFUND
}
