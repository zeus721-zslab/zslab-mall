package com.zslab.mall.stats.controller.response;

import java.util.List;

/**
 * 매출 통계 요약+추이 응답(Track 87·D-181·{@code GET /api/v1/admin/stats/sales}). compareSummary·compareTrend는 compare=NONE이거나
 * 비교 기간에 데이터가 0건이면 null(전역 NON_NULL이라 필드 생략)이다 — 0과 구분. compareTrend는 trend와 길이를 맞춰 인덱스로 대응한다(FE 점선 중첩).
 */
public record AdminSalesStatsResponse(
        SalesSummaryResponse summary,
        SalesSummaryResponse compareSummary,
        List<SalesTrendBucketResponse> trend,
        List<SalesTrendBucketResponse> compareTrend) {
}
