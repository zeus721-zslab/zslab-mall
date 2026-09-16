package com.zslab.mall.order.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 주문 목록 항목 응답(D-55·§11 그룹화 비적용). previewTitle은 서버 생성 문자열(null 금지·§15).
 */
public record OrderSummaryResponse(
        String orderId,
        String previewTitle,
        int sellerCount,
        long totalPrice,
        StatusView status,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime orderedAt) {

    /**
     * fetch join 로딩된 Order로 요약을 조립한다. productName은 order_item.product_name 스냅샷(Track 76·V22)이라 product 조회가 불필요하다.
     * previewTitle: OrderItem created_at ASC(동률 시 id ASC) 첫 행 productName + (2건 이상) " 외 N건"(D-55).
     */
    public static OrderSummaryResponse from(Order order) {
        List<OrderItem> orderedItems = order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getCreatedAt).thenComparing(OrderItem::getId))
                .toList();
        long sellerCount = orderedItems.stream().map(OrderItem::getSellerId).distinct().count();
        return new OrderSummaryResponse(
                order.getPublicId(),
                buildPreviewTitle(orderedItems),
                (int) sellerCount,
                order.getTotalPrice(),
                StatusView.of(order.getStatus()),
                order.getOrderedAt());
    }

    private static String buildPreviewTitle(List<OrderItem> orderedItems) {
        if (orderedItems.isEmpty()) {
            return "";   // ORD-1(주문 최소 1품목)로 실제 미발생·null 금지 방어값
        }
        String firstName = orderedItems.get(0).getProductName();
        if (orderedItems.size() == 1) {
            return firstName;
        }
        return firstName + " 외 " + (orderedItems.size() - 1) + "건";
    }
}
