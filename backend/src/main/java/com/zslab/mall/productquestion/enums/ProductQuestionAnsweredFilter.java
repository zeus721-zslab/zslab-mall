package com.zslab.mall.productquestion.enums;

/**
 * 셀러 질문 목록의 답변 여부 필터(Track 106-2). 요청 파라미터 enum 직접 바인딩(오값 400 MALFORMED_REQUEST).
 */
public enum ProductQuestionAnsweredFilter {

    ALL,
    UNANSWERED,
    ANSWERED;

    /** 저장소 조건값: 전체면 null, 그 밖은 "답변이 있어야 하는가". */
    public Boolean answeredCondition() {
        return this == ALL ? null : this == ANSWERED;
    }
}
