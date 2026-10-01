package com.zslab.mall.inventory.repository;

/** variant별 가용 재고 값(D-250 클레임 제안 입력 · 엔티티 미적재). */
public interface InventoryAvailableProjection {

    Long getVariantId();

    int getQuantityAvailable();
}
