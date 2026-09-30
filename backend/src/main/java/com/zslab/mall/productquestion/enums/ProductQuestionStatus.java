package com.zslab.mall.productquestion.enums;

/**
 * 상품 질문 공개 상태(Track 106-2·V41 chk_product_question_status). 등록 시 VISIBLE이며 관리자만 숨김·숨김 해제한다. 질문자 삭제는 상태가
 * 아니라 soft delete(deleted_at)로 표현한다. 답변 여부는 상태가 아니라 답변 컬럼 유무로 본다(숨김과 별개 축).
 */
public enum ProductQuestionStatus {

    VISIBLE,
    HIDDEN;

    /** 관리자 전이 합법성: 같은 상태로의 재요청은 불법(리뷰 선례·422). */
    public boolean canTransitionTo(ProductQuestionStatus target) {
        return target != null && target != this;
    }
}
