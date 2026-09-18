package com.zslab.mall.settlement.enums;

/**
 * 정산 품목 유형(Track 85). DDL {@code settlement_item.item_type} ENUM 정합.
 * SALE = 기간 내 구매확정(CONFIRMED) 품목 매출 · REFUND = 기간 내 완료(COMPLETED) 환불 차감(D-168 가드: 구매확정 이력 품목만).
 */
public enum SettlementItemType {
    SALE,
    REFUND
}
