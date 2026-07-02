package com.zslab.mall.inventory.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Seller 재고 출고 요청 DTO(Track 27 D-112). 형식 검증만 담당하며 수량 양수·실물 부족(INV-4) 등 재고 불변조건은
 * {@code InventoryService.markOutboundBySeller}·{@code Inventory.adjustStock} 도메인이 판정한다(형식/도메인 검증 분리).
 *
 * <p>quantity는 출고 수량(양수 크기)이며 내부에서 on_hand를 차감(-quantity)한다. qty≤0 거부는 도메인 규칙이므로 Service에서
 * {@code IllegalArgumentException}(→400)으로 차단한다({@code SellerInventoryMarkInboundRequest} 정합). reason은 감사 추적을
 * 위해 필수이며 {@code @Size(max=255)}는 V1 {@code inventory_history.reason VARCHAR(255)} 정합이다.
 */
public record SellerInventoryMarkOutboundRequest(
        int quantity,
        @NotBlank @Size(max = 255) String reason) {
}
