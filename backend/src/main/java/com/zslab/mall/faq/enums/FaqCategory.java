package com.zslab.mall.faq.enums;

/**
 * FAQ 카테고리(Track 106-3·V42 chk_faq_category). 고정 5종이며 구매자 채팅 도우미의 카테고리 칩 순서 = 선언 순서다 — 순서를 바꾸면 화면 칩 순서가
 * 바뀐다. 관리자는 카테고리를 추가·편집하지 않는다(카테고리 안 FAQ만 편집).
 */
public enum FaqCategory {

    ORDER_PAYMENT,
    DELIVERY,
    CLAIM,
    ACCOUNT,
    REVIEW_QUESTION
}
