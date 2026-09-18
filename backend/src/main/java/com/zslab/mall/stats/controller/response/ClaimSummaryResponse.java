package com.zslab.mall.stats.controller.response;

/**
 * 기간 클레임 요약(Track 88·D-182). claimCount = 기간 내 요청(requested_at) 클레임 건수(재요청 포함), paidItemCount = 기간 내 결제 품목 라인 수(분모),
 * claimRate = claimCount / paidItemCount × 100. refundAmount·refundCount = COMPLETED 환불(refunded_at 귀속), refundRate = refundAmount / 매출 × 100
 * (금액 기준 주 지표·매출 정의 D-180). 비율은 % 소수 2자리·분모 0이면 0.
 */
public record ClaimSummaryResponse(
        long claimCount,
        double claimRate,
        long refundAmount,
        double refundRate,
        long refundCount,
        long paidItemCount) {

    public static ClaimSummaryResponse of(long claimCount, long paidItemCount, long refundAmount, long refundCount,
            long revenue) {
        return new ClaimSummaryResponse(claimCount, StatsRatio.percent(claimCount, paidItemCount), refundAmount,
                StatsRatio.percent(refundAmount, revenue), refundCount, paidItemCount);
    }

    /** 비교 기간 데이터 유무(클레임·환불·결제 품목 모두 0이면 없음 → 응답 null). getter 패턴 이름은 Jackson 누출 때문에 피한다(Track 87). */
    public boolean hasNoData() {
        return claimCount == 0 && refundCount == 0 && paidItemCount == 0;
    }
}
