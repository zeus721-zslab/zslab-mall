package com.zslab.mall.stats.controller.response;

import java.util.List;

/**
 * 셀러 주문·클레임 통계 응답(Track 90-E-2·D-200·{@code GET /api/v1/seller/stats/orders}). 관리자 {@link AdminOrderStatsResponse}를 셀러 품목 단위로
 * 좁힌 형태 — 퍼널은 결제→출고→배송완료 3단계, 소요시간은 결제→출고·출고→배송완료 2종(클레임 요청→종결·구매확정·취소/반품 종결은 셀러 사양 밖),
 * 클레임 상품별 분해(claimByProduct)는 셀러에만 있다. 비교 기간은 클레임 요약·추이에만 적용하며 compareClaimSummary·compareClaimTrend는
 * compare=NONE이거나 비교 기간에 데이터가 0건이면 null(전역 NON_NULL이라 필드 생략·D-181 규약). 요약·추이·분포 record는 관리자 것을 재사용한다.
 */
public record SellerOrderStatsResponse(
        SellerOrderFunnelResponse funnel,
        SellerOrderLeadTimeResponse leadTime,
        ClaimSummaryResponse claimSummary,
        ClaimSummaryResponse compareClaimSummary,
        List<ClaimTrendBucketResponse> claimTrend,
        List<ClaimTrendBucketResponse> compareClaimTrend,
        List<ClaimTypeShareResponse> claimByType,
        List<ClaimReasonShareResponse> claimByReason,
        List<SellerClaimProductShareResponse> claimByProduct) {
}
