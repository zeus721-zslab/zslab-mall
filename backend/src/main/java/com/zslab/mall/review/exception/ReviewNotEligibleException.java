package com.zslab.mall.review.exception;

/**
 * 리뷰 행위 자격이 없을 때 발생한다(422 REVIEW_NOT_ELIGIBLE). 구매확정 전 품목의 리뷰 작성·본인 리뷰의 도움됐어요가 해당한다.
 */
public class ReviewNotEligibleException extends RuntimeException {

    public ReviewNotEligibleException(String message) {
        super(message);
    }
}
