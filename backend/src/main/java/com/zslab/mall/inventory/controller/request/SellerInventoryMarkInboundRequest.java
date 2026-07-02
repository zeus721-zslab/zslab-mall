package com.zslab.mall.inventory.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Seller 재고 입고 요청 DTO(Track 27 D-112). 형식 검증만 담당하며 수량 양수·재고 불변조건(INV-1·INV-4)은
 * {@code InventoryService.markInboundBySeller}·{@code Inventory.adjustStock} 도메인이 판정한다(형식/도메인 검증 분리).
 *
 * <p>quantity는 입고 수량(양수 크기)이며, qty≤0 거부는 도메인 규칙이므로 Bean Validation이 아닌 Service에서
 * {@code IllegalArgumentException}(→400)으로 차단한다({@code AdminInventoryAdjustRequest}가 delta=0을 도메인에서 거부하는
 * 패턴 정합). reason은 감사 추적을 위해 필수이며 {@code @Size(max=255)}는 V1 {@code inventory_history.reason VARCHAR(255)} 정합이다.
 */
public record SellerInventoryMarkInboundRequest(
        int quantity,
        @NotBlank @Size(max = 255) String reason) {
}
