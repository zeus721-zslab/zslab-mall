package com.zslab.mall.seller.enums;

/**
 * 판매자 종료(TERMINATED) 차단 사유(Track 89-D·D-187 종료 가드 3종). 관리자 상세 응답(종료 가능 여부 미리보기)과 409 응답의
 * {@code blocks} 항목이 같은 코드를 쓴다.
 */
public enum SellerTerminationBlockCode {
    /** G1: 미지급 정산(PENDING·CONFIRMED) 존재. */
    UNPAID_SETTLEMENT,
    /** G2: 진행 중 주문 품목 존재(종결 아님 ∧ 주문이 미결제 종료·취소 아님). */
    ORDER_ITEM_IN_PROGRESS,
    /** G3: 활성 클레임(REQUESTED·APPROVED) 존재. */
    CLAIM_ACTIVE
}
