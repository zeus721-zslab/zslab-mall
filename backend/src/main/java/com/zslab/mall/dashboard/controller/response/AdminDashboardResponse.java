package com.zslab.mall.dashboard.controller.response;

import java.util.List;

/**
 * 관리자 대시보드 단일 응답(Track 86·D-180). 실시간 집계 결과를 1회 응답에 모두 담는다(섹션별 엔드포인트 분리 없음·
 * 현 데이터 규모에서 응답 시간 합산 무시 가능·FE 로딩 상태 단순화).
 */
public record AdminDashboardResponse(
        DashboardSummaryResponse summary,
        DashboardPendingResponse pending,
        List<DashboardMonthlyRevenueResponse> monthlyRevenue,
        List<DashboardDailyOrdersResponse> dailyOrders,
        List<DashboardRecentOrderResponse> recentOrders,
        List<DashboardRecentClaimResponse> recentClaims,
        List<DashboardTopSellerResponse> topSellers,
        List<DashboardTopProductResponse> topProducts) {
}
