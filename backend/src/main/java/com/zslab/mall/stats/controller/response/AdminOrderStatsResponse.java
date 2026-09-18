package com.zslab.mall.stats.controller.response;

import java.util.List;

/**
 * 주문·클레임 통계 응답(Track 88·D-182·{@code GET /api/v1/admin/stats/orders}). 비교 기간은 클레임 요약·추이에만 적용하며 퍼널·소요시간·
 * 분포에는 비교 필드가 없다. compareClaimSummary·compareClaimTrend는 compare=NONE이거나 비교 기간에 데이터가 0건이면 null(전역 NON_NULL이라
 * 필드 생략·D-181 규약). compareClaimTrend는 claimTrend와 길이를 맞춰 인덱스로 대응한다.
 */
public record AdminOrderStatsResponse(
        OrderFunnelResponse funnel,
        OrderLeadTimeResponse leadTime,
        ClaimSummaryResponse claimSummary,
        ClaimSummaryResponse compareClaimSummary,
        List<ClaimTrendBucketResponse> claimTrend,
        List<ClaimTrendBucketResponse> compareClaimTrend,
        List<ClaimTypeShareResponse> claimByType,
        List<ClaimReasonShareResponse> claimByReason) {
}
