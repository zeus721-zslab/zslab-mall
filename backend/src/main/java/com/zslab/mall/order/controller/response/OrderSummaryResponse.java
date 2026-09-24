package com.zslab.mall.order.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.seller.entity.Seller;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 주문 목록 항목 응답(D-55·§11 그룹화 비적용). previewTitle은 서버 생성 문자열(null 금지·§15).
 * orderNo는 사람이 읽는 주문번호(Track 105-4g-3·추가형 필드 — 화면 표시용, 링크·라우팅은 orderId 유지).
 */
public record OrderSummaryResponse(
        String orderId,
        String orderNo,
        String previewTitle,
        int sellerCount,
        long totalPrice,
        StatusView status,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime orderedAt,
        List<ActiveClaimCount> activeClaims,
        List<ItemSummary> items) {

    /**
     * 주문에 걸린 진행 중(REQUESTED·APPROVED) 클레임의 유형별 건수(Track 101-B 카드 배지). 건수가 0인 유형은 항목 자체가 없고,
     * 진행 중 클레임이 없는 주문은 빈 목록이다(null 금지·§15). 표시 규칙(2종까지·초과 시 "외 N")은 FE가 접는다.
     */
    public record ActiveClaimCount(ClaimType claimType, int count) {
    }

    /**
     * 주문 카드의 품목 요약(Track 105-2d·추가형 필드). 식별자는 public_id이며 삭제된 상품·variant·셀러는 null이다
     * (null 필드는 전역 NON_NULL로 응답에서 키가 생략된다 — 기존 delivery와 같다).
     * 배송 정보·진행 클레임 id는 넣지 않는다 — 목록 버튼은 구매 확정·클레임 신청만이고 배송 조회·요청 상세는 주문 상세가 담당한다(D-223).
     */
    public record ItemSummary(
            String orderItemId,
            String productName,
            String optionLabel,
            int quantity,
            long unitPrice,
            long totalPrice,
            StatusView status,
            String sellerName,
            String thumbnailUrl,
            String productId,
            String variantId,
            boolean exchangeCompleted) {
    }

    /**
     * fetch join 로딩된 Order로 요약을 조립한다. productName은 order_item.product_name 스냅샷(Track 76·V22)이라 product 조회가 불필요하다.
     * previewTitle: OrderItem created_at ASC(동률 시 id ASC) 첫 행 productName + (2건 이상) " 외 N건"(D-55). items도 같은 순서다.
     *
     * @param activeClaims 이 주문의 진행 중 클레임 유형별 건수(페이지 단위 배치 조회 결과·없으면 빈 목록)
     * @param productById 페이지 품목의 상품(삭제 상품은 없음) · variantById·sellerById도 같은 배치 조회 결과
     * @param exchangeCompletedItemIds 완료된 교환이 있는 품목 id
     */
    public static OrderSummaryResponse from(
            Order order,
            List<ActiveClaimCount> activeClaims,
            Map<Long, Product> productById,
            Map<Long, ProductVariant> variantById,
            Map<Long, Seller> sellerById,
            Set<Long> exchangeCompletedItemIds) {
        List<OrderItem> orderedItems = order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getCreatedAt).thenComparing(OrderItem::getId))
                .toList();
        long sellerCount = orderedItems.stream().map(OrderItem::getSellerId).distinct().count();
        List<ItemSummary> items = orderedItems.stream()
                .map(item -> toItemSummary(item, productById, variantById, sellerById, exchangeCompletedItemIds))
                .toList();
        return new OrderSummaryResponse(
                order.getPublicId(),
                order.getOrderNo(),
                buildPreviewTitle(orderedItems),
                (int) sellerCount,
                order.getTotalPrice(),
                StatusView.of(order.getStatus()),
                order.getOrderedAt(),
                activeClaims,
                items);
    }

    private static ItemSummary toItemSummary(
            OrderItem item,
            Map<Long, Product> productById,
            Map<Long, ProductVariant> variantById,
            Map<Long, Seller> sellerById,
            Set<Long> exchangeCompletedItemIds) {
        Product product = productById.get(item.getProductId());
        ProductVariant variant = variantById.get(item.getVariantId());
        Seller seller = sellerById.get(item.getSellerId());
        return new ItemSummary(
                item.getPublicId(),
                item.getProductName(),
                item.getOptionLabel(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getTotalPrice(),
                StatusView.of(item.getItemStatus()),
                seller != null ? seller.getCompanyName() : null,
                product != null ? product.getThumbnailUrl() : null,
                product != null ? product.getPublicId() : null,
                variant != null ? variant.getPublicId() : null,
                exchangeCompletedItemIds.contains(item.getId()));
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
