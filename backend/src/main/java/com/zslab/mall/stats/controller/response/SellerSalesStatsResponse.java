package com.zslab.mall.stats.controller.response;

import java.util.List;

/**
 * 셀러 매출 통계 요약+추이 응답(Track 90-E-1·D-200·{@code GET /api/v1/seller/stats/sales}). 형태는 관리자 {@link AdminSalesStatsResponse}와
 * 같고 값의 정의만 셀러 품목 축(D-192: 자기 품목 total_price 합·COUNT DISTINCT order)이다. compareSummary·compareTrend는 compare=NONE이거나
 * 비교 기간에 데이터가 0건이면 null(전역 NON_NULL이라 필드 생략)이며 compareTrend는 trend와 길이를 맞춰 인덱스로 대응한다.
 */
public record SellerSalesStatsResponse(
        SalesSummaryResponse summary,
        SalesSummaryResponse compareSummary,
        List<SalesTrendBucketResponse> trend,
        List<SalesTrendBucketResponse> compareTrend) {
}
