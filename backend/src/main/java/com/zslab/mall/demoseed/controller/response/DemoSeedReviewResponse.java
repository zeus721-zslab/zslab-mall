package com.zslab.mall.demoseed.controller.response;

import java.util.List;
import java.util.Map;

/**
 * 데모 리뷰 적재 결과(D-245). dryRun이면 계획만 담고 createdCount는 0이다. 분포는 계획 기준이다.
 *
 * @param dryRun             계획만 산출했는지
 * @param publicCountBefore  호출 전 데모 구매자 공개 리뷰 수
 * @param target             목표 공개 리뷰 수
 * @param candidateCount     리뷰를 쓸 수 있는 후보 품목 수
 * @param plannedCount       계획한 리뷰 수(후보·문구가 모자라면 부족분보다 적다)
 * @param createdCount       이번 호출에서 만든 리뷰 수(dryRun 0)
 * @param byBuyer            계획의 구매자별 건수(데모 도메인 이메일)
 * @param byRating           계획의 별점별 건수
 * @param failedItems        실패해 롤백된 품목과 사유
 */
public record DemoSeedReviewResponse(
        boolean dryRun,
        long publicCountBefore,
        int target,
        int candidateCount,
        int plannedCount,
        int createdCount,
        Map<String, Integer> byBuyer,
        Map<Integer, Integer> byRating,
        List<FailedItem> failedItems) {

    public record FailedItem(String orderItemPublicId, String reason) {
    }
}
