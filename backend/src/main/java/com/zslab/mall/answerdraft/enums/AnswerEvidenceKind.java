package com.zslab.mall.answerdraft.enums;

/**
 * 답안 초안 근거 종류(D-253 · 응답 전용 · DB 컬럼 없음). FE {@code ANSWER_EVIDENCE_KINDS}와 1:1.
 */
public enum AnswerEvidenceKind {
    /** 공개 FAQ 키워드 일치. */
    FAQ,
    /** 같은 상품의 답변된 질문(상품 Q&A만). */
    ANSWERED_QUESTION,
    /** 문의에 첨부된 주문의 주문·배송 상태(1:1 문의만). */
    ORDER
}
