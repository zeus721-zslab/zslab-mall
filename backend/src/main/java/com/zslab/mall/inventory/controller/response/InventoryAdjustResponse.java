package com.zslab.mall.inventory.controller.response;

import com.zslab.mall.inventory.entity.Inventory;

/**
 * Admin 재고 조정 응답 DTO(Track 21 D-105 §4). 조정 후 재고 수치(on_hand·reserved·available)를 노출한다.
 *
 * <p>내부 PK(variantId)·InventoryHistory는 노출하지 않는다(Controller 단일 책임·내부 ID 노출 회피·D-40 §2).
 * variantPublicId는 요청 경로 식별자를 그대로 반향한다.
 */
public record InventoryAdjustResponse(
        String variantPublicId,
        int quantityOnHand,
        int quantityReserved,
        int quantityAvailable) {

    public static InventoryAdjustResponse from(String variantPublicId, Inventory inventory) {
        return new InventoryAdjustResponse(
                variantPublicId,
                inventory.getQuantityOnHand(),
                inventory.getQuantityReserved(),
                inventory.getQuantityAvailable());
    }
}
