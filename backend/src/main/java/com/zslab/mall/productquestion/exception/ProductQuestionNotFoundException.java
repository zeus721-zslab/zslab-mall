package com.zslab.mall.productquestion.exception;

/**
 * 상품 질문을 찾을 수 없을 때 발생한다(404 PRODUCT_QUESTION_NOT_FOUND). 삭제·타인 질문(질문자 수정·삭제)·다른 셀러 상품의 질문(답변)도 같은
 * 404로 은닉한다.
 */
public class ProductQuestionNotFoundException extends RuntimeException {

    public ProductQuestionNotFoundException(String message) {
        super(message);
    }
}
