package com.zslab.mall.review.exception;

/**
 * 리뷰를 찾을 수 없을 때 발생한다(404 REVIEW_NOT_FOUND). 삭제·숨김(공개 조회·도움됐어요)·타인 리뷰(수정·삭제)도 같은 404로 은닉한다.
 */
public class ReviewNotFoundException extends RuntimeException {

    public ReviewNotFoundException(String message) {
        super(message);
    }
}
