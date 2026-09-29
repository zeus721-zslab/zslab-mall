package com.zslab.mall.review.exception;

/**
 * 관리자 숨김·숨김 해제가 현재 상태와 맞지 않을 때 발생한다(422 REVIEW_INVALID_STATE·같은 상태 재요청). 엔티티 전이의
 * {@link IllegalStateException}을 서비스가 이 예외로 바꾼다(전역 매핑 없는 IllegalStateException은 500).
 */
public class ReviewInvalidStateException extends RuntimeException {

    public ReviewInvalidStateException(String message) {
        super(message);
    }
}
