package com.zslab.mall.order.controller.response;

import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.review.enums.ReviewEligibility;
import java.util.Map;

/**
 * 주문 품목의 리뷰 상태(Track 106-1·구매자 주문 상세·목록 공통). 리뷰가 있으면(삭제 포함 — 재작성 불가) WRITTEN이고, 없으면 구매확정 품목만
 * WRITABLE, 그 밖은 NOT_ELIGIBLE이다. reviewId는 WRITTEN이면서 삭제되지 않은 리뷰만 채운다(삭제 리뷰는 키 생략).
 */
public record OrderItemReviewResponse(ReviewEligibility status, String reviewId) {

    /**
     * @param reviewIdByItemId 페이지 품목의 리뷰(품목 id → 리뷰 public_id·삭제 리뷰는 값 null). 키가 있으면 리뷰가 있었던 것이다
     */
    public static OrderItemReviewResponse of(OrderItem item, Map<Long, String> reviewIdByItemId) {
        if (reviewIdByItemId.containsKey(item.getId())) {
            return new OrderItemReviewResponse(ReviewEligibility.WRITTEN, reviewIdByItemId.get(item.getId()));
        }
        ReviewEligibility status = item.getItemStatus() == OrderItemStatus.CONFIRMED
                ? ReviewEligibility.WRITABLE
                : ReviewEligibility.NOT_ELIGIBLE;
        return new OrderItemReviewResponse(status, null);
    }
}
