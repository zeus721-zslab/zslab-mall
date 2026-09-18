package com.zslab.mall.settlement.controller.response;

import com.zslab.mall.order.controller.response.PagedResponse;
import java.util.List;

/**
 * 관리자 정산 목록 응답(Track 85). {@link PagedResponse} 5필드에 해당 월 합계 {@code totals}(상태·keyword 필터 무관)를 동봉한다
 * (AdminClaimListResponse 패턴).
 */
public record AdminSettlementListResponse(
        List<AdminSettlementSummaryResponse> items,
        int page,
        int size,
        long totalCount,
        boolean hasNext,
        SettlementMonthlyTotals totals) {

    public static AdminSettlementListResponse from(PagedResponse<AdminSettlementSummaryResponse> page,
            SettlementMonthlyTotals totals) {
        return new AdminSettlementListResponse(page.items(), page.page(), page.size(), page.totalCount(), page.hasNext(),
                totals);
    }
}
