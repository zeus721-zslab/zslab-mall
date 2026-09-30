package com.zslab.mall.productquestion.exception;

/**
 * 상품 질문의 현재 상태에서 할 수 없는 명령일 때 발생한다(422 PRODUCT_QUESTION_INVALID_STATE). 답변 뒤 질문자 수정·삭제, 숨김 질문 수정·답변,
 * 관리자 같은 상태 재요청. 엔티티의 {@link IllegalStateException}을 서비스가 이 예외로 바꾼다(전역 매핑 없는 IllegalStateException은 500).
 */
public class ProductQuestionInvalidStateException extends RuntimeException {

    public ProductQuestionInvalidStateException(String message) {
        super(message);
    }
}
