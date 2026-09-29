package com.zslab.mall.review.exception;

/**
 * 리뷰 사진 업로드 요청의 Content-Length가 상한을 넘을 때 발생한다(413 PAYLOAD_TOO_LARGE). 멀티파트 파싱 전 필터
 * ({@code ReviewAttachmentRequestSizeFilter})가 던진다.
 */
public class ReviewAttachmentRequestTooLargeException extends RuntimeException {

    public ReviewAttachmentRequestTooLargeException(String message) {
        super(message);
    }
}
