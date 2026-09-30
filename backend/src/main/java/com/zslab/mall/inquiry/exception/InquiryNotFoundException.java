package com.zslab.mall.inquiry.exception;

/**
 * 운영자 문의를 찾을 수 없을 때 발생한다(404 INQUIRY_NOT_FOUND). 삭제·타인 문의(구매자 수정·삭제·답변 확인)도 같은 404로 은닉한다.
 */
public class InquiryNotFoundException extends RuntimeException {

    public InquiryNotFoundException(String message) {
        super(message);
    }
}
