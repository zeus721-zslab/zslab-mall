package com.zslab.mall.inquiry.enums;

/**
 * 운영자 문의 카테고리(Track 106-4·V43 chk_inquiry_category). 고정 5종이며 FAQ 카테고리와 별개다 — 문의는 취소·반품·교환(CLAIM) 선택 시
 * 화면이 주문 기능을 먼저 안내하고, 분류에 맞지 않는 문의를 위한 OTHER가 있다.
 */
public enum InquiryCategory {

    ORDER_PAYMENT,
    DELIVERY,
    CLAIM,
    ACCOUNT,
    OTHER
}
