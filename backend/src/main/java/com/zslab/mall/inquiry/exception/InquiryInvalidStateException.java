package com.zslab.mall.inquiry.exception;

/**
 * 운영자 문의의 현재 상태에서 할 수 없는 명령일 때 발생한다(422 INQUIRY_INVALID_STATE). 답변 뒤 구매자 수정·삭제, 미답변 문의 답변 확인.
 * 엔티티의 {@link IllegalStateException}을 서비스가 이 예외로 바꾼다(전역 매핑 없는 IllegalStateException은 500).
 */
public class InquiryInvalidStateException extends RuntimeException {

    public InquiryInvalidStateException(String message) {
        super(message);
    }
}
