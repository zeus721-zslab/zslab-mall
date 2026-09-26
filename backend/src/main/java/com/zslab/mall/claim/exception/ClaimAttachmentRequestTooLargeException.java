package com.zslab.mall.claim.exception;

/**
 * 구매자 첨부 업로드 요청의 Content-Length가 합계 상한을 넘을 때 발생한다(D-230). 멀티파트 파싱 전 필터가 던지며, 전역 예외 핸들러가
 * HTTP 413 {@code PAYLOAD_TOO_LARGE}로 응답한다.
 */
public class ClaimAttachmentRequestTooLargeException extends RuntimeException {

    public ClaimAttachmentRequestTooLargeException(String message) {
        super(message);
    }
}
