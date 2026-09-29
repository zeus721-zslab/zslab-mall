package com.zslab.mall.review.exception;

/**
 * 품목에 이미 리뷰가 있을 때 발생한다(409 REVIEW_ALREADY_EXISTS). 삭제한 리뷰도 포함한다(재작성 불가). 동시 작성은 uk_review_order_item 위반으로
 * 같은 예외가 된다.
 */
public class ReviewAlreadyExistsException extends RuntimeException {

    public ReviewAlreadyExistsException(String message) {
        super(message);
    }
}
