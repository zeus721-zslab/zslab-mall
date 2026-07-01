package com.zslab.mall.inventory.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin 재고 조정 요청 DTO(Track 21 D-105 §4). 형식 검증만 담당하며 재고 불변조건(INV-1·INV-4)·조정 정책은
 * {@code InventoryService}·{@code Inventory} 도메인이 판정한다.
 *
 * <p>quantityDelta는 부호 있는 증감량(양수=입고·음수=차감)이며, delta=0 거부는 도메인 규칙이므로 Bean Validation이 아닌
 * {@code Inventory.adjustStock}에서 {@code IllegalArgumentException}(→400)으로 차단한다(형식/도메인 검증 분리). reason은
 * 감사 추적을 위해 필수이며 {@code @Size(max=255)}는 V1 {@code inventory_history.reason VARCHAR(255)} 정합이다.
 */
public record AdminInventoryAdjustRequest(
        int quantityDelta,
        @NotBlank @Size(max = 255) String reason) {
}
