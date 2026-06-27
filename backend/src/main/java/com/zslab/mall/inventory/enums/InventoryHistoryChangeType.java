package com.zslab.mall.inventory.enums;

/**
 * 재고 변동 유형 — V1 DDL ENUM 정합 (4층위 Layer 2·inventory_history.change_type).
 */
public enum InventoryHistoryChangeType {
    ORDER,
    CANCEL,
    RETURN,
    ADJUST,
    INBOUND,
    OUTBOUND
}
