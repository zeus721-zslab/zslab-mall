package com.zslab.mall.settlement.enums;

/**
 * 정산 품목 유형(Track 85). DDL {@code settlement_item.item_type} ENUM 정합(V37).
 * SALE = 구매확정(CONFIRMED) 품목 매출 · REFUND = 완료(COMPLETED) 환불 차감(D-168 가드: 구매확정 이력 품목만) ·
 * CARRYOVER = 순지급액 음수로 지급이 막힌(CONFIRMED) 앞선 정산의 부족분 이월 차감(Track 104-3b).
 */
public enum SettlementItemType {
    SALE,
    REFUND,
    CARRYOVER
}
