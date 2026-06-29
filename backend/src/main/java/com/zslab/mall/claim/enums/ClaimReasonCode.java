package com.zslab.mall.claim.enums;

/**
 * 클레임 사유 코드(Track 9 CANCEL 한정·6값·D-89 Q5). DTO 검증 단일 소스다.
 *
 * <p>CLAIM_REASON Code 테이블 시드 부재(정찰 실측 9·V1__init.sql INSERT 0건)로 ENUM 신설했다(D-89 Q5·기조 4).
 * claim.reason_code 컬럼은 varchar(50)이며 본 enum 이름({@code name()})을 저장한다.
 *
 * <p>Track 11 RETURN/EXCHANGE 진입 시 확장 예정: PRODUCT_DEFECT·DAMAGED_ON_ARRIVAL·WRONG_PRODUCT·DELIVERY_DELAY.
 * Code 테이블 전환은 시드 등록 부담 발생 시점에 별도 트랙에서 검토한다.
 */
public enum ClaimReasonCode {
    /** 단순 변심. */
    BUYER_CHANGED_MIND,
    /** 중복 주문. */
    DUPLICATE_ORDER,
    /** 결제 문제. */
    PAYMENT_ISSUE,
    /** 주문 실수(수량·옵션 오선택 등). */
    ORDER_MISTAKE,
    /** 재고/배송 지연. */
    STOCK_DELAY,
    /** 기타. */
    OTHER
}
