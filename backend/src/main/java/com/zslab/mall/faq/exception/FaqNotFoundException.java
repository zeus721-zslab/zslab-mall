package com.zslab.mall.faq.exception;

/** FAQ 미존재·삭제(Track 106-3). 전역 예외 핸들러가 404 FAQ_NOT_FOUND로 응답한다. */
public class FaqNotFoundException extends RuntimeException {

    public FaqNotFoundException(String message) {
        super(message);
    }
}
