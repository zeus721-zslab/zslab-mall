package com.zslab.mall.stats.controller.response;

/**
 * 클레임 추이 구간 1행(Track 88). bucketKey·bucketLabel 규약은 매출 추이와 같다({@code StatsBuckets}). claimCount는 requested_at,
 * 분모(결제 품목·매출)는 paid_at, 환불은 refunded_at 구간이라 각각 집계 후 키로 합친다. 빈 구간은 0.
 */
public record ClaimTrendBucketResponse(
        String bucketKey,
        String bucketLabel,
        long claimCount,
        double claimRate,
        long refundAmount,
        double refundRate,
        long refundCount) {

    public static ClaimTrendBucketResponse of(String bucketKey, String bucketLabel, long claimCount, long paidItemCount,
            long refundAmount, long refundCount, long revenue) {
        return new ClaimTrendBucketResponse(bucketKey, bucketLabel, claimCount, StatsRatio.percent(claimCount, paidItemCount),
                refundAmount, StatsRatio.percent(refundAmount, revenue), refundCount);
    }
}
