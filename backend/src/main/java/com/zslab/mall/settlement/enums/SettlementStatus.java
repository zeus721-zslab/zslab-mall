package com.zslab.mall.settlement.enums;

/**
 * 정산 상태(A#5·SETTLEMENT_STATUS·STL-2·3값). DDL {@code settlement.status} ENUM 정합.
 */
public enum SettlementStatus {
    PENDING,
    CONFIRMED,
    PAID
}
