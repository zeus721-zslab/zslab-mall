package com.zslab.mall.review.exception;

/**
 * 리뷰 사진 업로드 요청에 Content-Length 없이 청크 전송이 올 때 발생한다(411 LENGTH_REQUIRED·본문 크기를 판정할 수 없음).
 */
public class ReviewAttachmentLengthRequiredException extends RuntimeException {

    public ReviewAttachmentLengthRequiredException(String message) {
        super(message);
    }
}
