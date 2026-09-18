package com.zslab.mall.stats.controller.response;

/**
 * 추이 구간 1행(Track 87). bucketKey는 내부 키(일 yyyy-MM-dd·주 yyyy-'W'ww(ISO)·월 yyyy-MM), bucketLabel은 화면 표기용
 * (일·월은 키와 같고 주는 주 시작일(월요일) yyyy-MM-dd). 빈 구간은 0으로 채워 구간 수를 고정한다.
 */
public record SalesTrendBucketResponse(
        String bucketKey,
        String bucketLabel,
        long revenue,
        long refund,
        long netRevenue,
        long orderCount) {

    public static SalesTrendBucketResponse of(String bucketKey, String bucketLabel, long revenue, long refund,
            long orderCount) {
        return new SalesTrendBucketResponse(bucketKey, bucketLabel, revenue, refund, revenue - refund, orderCount);
    }
}
