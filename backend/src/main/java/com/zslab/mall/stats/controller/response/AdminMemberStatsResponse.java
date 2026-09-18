package com.zslab.mall.stats.controller.response;

import java.util.List;

/**
 * 회원 통계 응답(Track 88·D-182·{@code GET /api/v1/admin/stats/members}). 비교 기간은 summary·signupTrend에만 적용하며 등급 분포·구매자 분리·
 * 상위 회원에는 비교 필드가 없다. compareSummary·compareSignupTrend는 compare=NONE이거나 비교 기간에 데이터가 0건이면 null(전역 NON_NULL이라
 * 생략·D-181 규약). 등급은 buyer_profile의 현재 등급 경유(주문 시점 등급 스냅샷 없음 — 등급 재산정 시 과거 매출의 등급 귀속이 바뀐다).
 */
public record AdminMemberStatsResponse(
        MemberSummaryResponse summary,
        MemberSummaryResponse compareSummary,
        List<SignupTrendBucketResponse> signupTrend,
        List<SignupTrendBucketResponse> compareSignupTrend,
        List<GradeDistributionResponse> gradeDistribution,
        BuyerSplitResponse buyerSplit,
        List<TopBuyerResponse> topBuyers) {
}
