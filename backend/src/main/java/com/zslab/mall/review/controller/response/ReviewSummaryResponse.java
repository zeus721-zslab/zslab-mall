package com.zslab.mall.review.controller.response;

import java.util.List;

/**
 * 상품 리뷰 요약(Track 106-1·공개 리뷰 기준). averageRating은 리뷰가 없으면 null(키 생략). ratingDistribution은 별점 5→1 순서로 5칸 모두
 * 싣는다. summaryText가 없으면(리뷰 0건·아직 계산 전) 키 생략 = "요약 없음".
 */
public record ReviewSummaryResponse(
        long reviewCount,
        Double averageRating,
        List<RatingCount> ratingDistribution,
        List<KeywordCount> keywords,
        List<RecentPhoto> recentPhotos,
        String summaryText) {

    public record RatingCount(int rating, long count) {
    }

    public record KeywordCount(String code, String label, long count) {
    }

    public record RecentPhoto(String reviewId, String url, String thumbnailUrl) {
    }
}
