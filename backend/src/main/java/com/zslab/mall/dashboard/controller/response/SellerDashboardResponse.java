package com.zslab.mall.dashboard.controller.response;

import java.util.List;

/**
 * 셀러 대시보드 단일 응답(Track 90-B-2). 관리자 {@code AdminDashboardResponse}와 별도 record다 — 재사용하면 관리자에 필드가 늘 때
 * 셀러에게 조용히 샌다(D-191 원칙). 요약·일별 추이·상위 상품은 {@code period} 기간 기준, 처리 대기·최근 목록은 기간 무관 실시간이다.
 */
public record SellerDashboardResponse(
        SellerDashboardPeriodResponse period,
        SellerDashboardSummaryResponse summary,
        SellerDashboardPendingResponse pending,
        List<SellerDashboardDailyTrendResponse> dailyTrend,
        List<SellerDashboardRecentOrderItemResponse> recentOrderItems,
        List<SellerDashboardRecentClaimResponse> recentClaims,
        List<SellerDashboardTopProductResponse> topProducts) {
}
