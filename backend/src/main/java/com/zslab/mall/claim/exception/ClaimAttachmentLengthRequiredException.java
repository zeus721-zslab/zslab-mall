package com.zslab.mall.claim.exception;

/**
 * 구매자 첨부 업로드 요청에 Content-Length가 없을 때(청크 전송) 발생한다(D-230). 길이를 모르면 합계 상한 판정을 우회하므로 거부하며,
 * 전역 예외 핸들러가 HTTP 411 {@code LENGTH_REQUIRED}로 응답한다. 브라우저 업로드는 항상 길이를 보낸다.
 */
public class ClaimAttachmentLengthRequiredException extends RuntimeException {

    public ClaimAttachmentLengthRequiredException(String message) {
        super(message);
    }
}
