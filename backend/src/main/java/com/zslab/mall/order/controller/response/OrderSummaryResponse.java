package com.zslab.mall.order.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.claim.enums.ClaimType;
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
        LocalDateTime orderedAt,
        List<ActiveClaimCount> activeClaims) {

    /**
     * 주문에 걸린 진행 중(REQUESTED·APPROVED) 클레임의 유형별 건수(Track 101-B 카드 배지). 건수가 0인 유형은 항목 자체가 없고,
     * 진행 중 클레임이 없는 주문은 빈 목록이다(null 금지·§15). 표시 규칙(2종까지·초과 시 "외 N")은 FE가 접는다.
     */
    public record ActiveClaimCount(ClaimType claimType, int count) {
    }

    /**
     * fetch join 로딩된 Order로 요약을 조립한다. productName은 order_item.product_name 스냅샷(Track 76·V22)이라 product 조회가 불필요하다.
     * previewTitle: OrderItem created_at ASC(동률 시 id ASC) 첫 행 productName + (2건 이상) " 외 N건"(D-55).
     *
     * @param activeClaims 이 주문의 진행 중 클레임 유형별 건수(페이지 단위 배치 조회 결과·없으면 빈 목록)
     */
    public static OrderSummaryResponse from(Order order, List<ActiveClaimCount> activeClaims) {
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
                order.getOrderedAt(),
                activeClaims);
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
