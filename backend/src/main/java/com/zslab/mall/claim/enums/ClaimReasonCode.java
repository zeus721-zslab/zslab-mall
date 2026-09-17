package com.zslab.mall.claim.enums;

/**
 * 클레임 사유 코드(Track 9 CANCEL 신설·6값 → Track 14 RETURN/EXCHANGE 10값·D-89 Q5·D-98 Q6). DTO 검증 단일 소스다.
 *
 * <p>CLAIM_REASON Code 테이블 시드 부재(정찰 실측 9·V1__init.sql INSERT 0건)로 ENUM 신설했다(D-89 Q5·기조 4).
 * claim.reason_code 컬럼은 varchar(50)이며 본 enum 이름({@code name()})을 저장한다.
 *
 * <p>Track 14 RETURN/EXCHANGE 진입 시 PRODUCT_DEFECT·DAMAGED_ON_ARRIVAL·WRONG_PRODUCT·DELIVERY_DELAY 4값을 추가했다(D-98 Q6).
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
    /** 상품 결함·하자. */
    PRODUCT_DEFECT,
    /** 배송 중 파손. */
    DAMAGED_ON_ARRIVAL,
    /** 오배송(다른 상품 수령). */
    WRONG_PRODUCT,
    /** 배송 지연. */
    DELIVERY_DELAY,
    /** 기타. */
    OTHER;

    /**
     * 해당 클레임 유형의 요청 사유로 쓸 수 있는지 판정한다(Track 81-A D-170·R2 / Track 83 D-177 결정 7). RETURN·EXCHANGE는
     * 단순변심·상품불량·오배송 3값만 허용하며 CANCEL은 제한이 없다.
     */
    public boolean isApplicableTo(ClaimType claimType) {
        if (!claimType.isPickupBased()) {
            return true;
        }
        return this == BUYER_CHANGED_MIND || this == PRODUCT_DEFECT || this == WRONG_PRODUCT;
    }
}
