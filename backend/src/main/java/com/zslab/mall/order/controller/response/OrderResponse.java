package com.zslab.mall.order.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentMethod;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.seller.entity.Seller;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 주문 단건 응답(§11 seller 그룹화 + #6 shippingAddress 포함). 식별자 전부 public_id·내부 BIGINT 미노출.
 *
 * <p>입력은 items가 fetch join으로 로딩된 Order + enrich 데이터(Product·Variant·Seller by BIGINT id)다(D-41 입력 범위 제한).
 *
 * <p>Track 105-4g-3 추가 필드: orderNo(사람이 읽는 주문번호)·orderedAt·payment(결제 요약·미결제면 null → 전역 NON_NULL로 키 생략).
 */
public record OrderResponse(
        String orderId,
        StatusView status,
        List<SellerGroupResponse> sellers,
        long totalPrice,
        ShippingAddressResponse shippingAddress,
        String orderNo,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime orderedAt,
        PaymentSummary payment) {

    /** 결제 요약(수단 코드·결제 시각). 결제 시각이 있는 행만 만든다 — 환불로 CANCELLED가 된 행도 paidAt을 유지하므로 포함된다. */
    public record PaymentSummary(
            PaymentMethod method,
            @JsonSerialize(using = KstOffsetSerializer.class)
            LocalDateTime paidAt) {

        public static PaymentSummary from(Payment payment) {
            return payment == null ? null : new PaymentSummary(payment.getMethod(), payment.getPaidAt());
        }
    }

    public static OrderResponse fromOrderWithItems(
            Order order,
            Map<Long, Product> productById,
            Map<Long, ProductVariant> variantById,
            Map<Long, Seller> sellerById) {
        return fromOrderWithItems(order, productById, variantById, sellerById, Set.of(), Map.of(), null);
    }

    /**
     * {@link #fromOrderWithItems(Order, Map, Map, Map)} + 교환 완료 품목 id 집합(Track 83 D-177 보충·exchangeCompleted)
     * + 품목 id별 원 발송 Delivery(Track 96-2 D-203·delivery·없으면 null) + 결제 시각이 있는 최신 결제 행(Track 105-4g-3·없으면 null).
     */
    public static OrderResponse fromOrderWithItems(
            Order order,
            Map<Long, Product> productById,
            Map<Long, ProductVariant> variantById,
            Map<Long, Seller> sellerById,
            Set<Long> exchangeCompletedItemIds,
            Map<Long, Delivery> originalDeliveryByItemId,
            Payment paidPayment) {
        // seller_id 단위 그룹화(삽입 순서 보존). 단일 판매자도 배열 길이 1.
        Map<Long, List<OrderItem>> itemsBySeller = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            itemsBySeller.computeIfAbsent(item.getSellerId(), key -> new ArrayList<>()).add(item);
        }

        List<SellerGroupResponse> sellers = new ArrayList<>();
        for (Map.Entry<Long, List<OrderItem>> entry : itemsBySeller.entrySet()) {
            Seller seller = sellerById.get(entry.getKey());
            List<OrderItemResponse> items = new ArrayList<>();
            long subtotal = 0L;
            for (OrderItem item : entry.getValue()) {
                Product product = productById.get(item.getProductId());
                ProductVariant variant = variantById.get(item.getVariantId());
                items.add(new OrderItemResponse(
                        item.getPublicId(),
                        product != null ? product.getPublicId() : null,
                        item.getProductName(),
                        variant != null ? variant.getPublicId() : null,
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getTotalPrice(),
                        item.getOptionLabel(),
                        StatusView.of(item.getItemStatus()),
                        exchangeCompletedItemIds.contains(item.getId()),
                        OrderItemDeliveryResponse.from(originalDeliveryByItemId.get(item.getId())),
                        product != null ? product.getThumbnailUrl() : null));
                subtotal += item.getTotalPrice();
            }
            sellers.add(new SellerGroupResponse(
                    seller != null ? seller.getPublicId() : null,
                    seller != null ? seller.getCompanyName() : null,
                    items,
                    subtotal));
        }

        ShippingAddressResponse shippingAddress = order.getShippingSnapshot() != null
                ? ShippingAddressResponse.from(order.getShippingSnapshot())
                : null;

        return new OrderResponse(
                order.getPublicId(),
                StatusView.of(order.getStatus()),
                sellers,
                order.getTotalPrice(),
                shippingAddress,
                order.getOrderNo(),
                order.getOrderedAt(),
                PaymentSummary.from(paidPayment));
    }
}
