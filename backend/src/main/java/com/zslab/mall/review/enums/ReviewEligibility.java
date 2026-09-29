package com.zslab.mall.review.enums;

/**
 * 구매자 주문 응답의 품목별 리뷰 상태(Track 106-1). 구매확정(CONFIRMED) 전이면 NOT_ELIGIBLE, 확정됐고 리뷰가 없으면 WRITABLE, 리뷰가 있으면
 * (삭제됐어도 — 재작성 불가) WRITTEN이다.
 */
public enum ReviewEligibility {

    NOT_ELIGIBLE,
    WRITABLE,
    WRITTEN
}
