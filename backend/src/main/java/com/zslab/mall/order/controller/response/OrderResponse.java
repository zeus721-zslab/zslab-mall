package com.zslab.mall.order.controller.response;

import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.seller.entity.Seller;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 주문 단건 응답(§11 seller 그룹화 + #6 shippingAddress 포함). 식별자 전부 public_id·내부 BIGINT 미노출.
 *
 * <p>입력은 items가 fetch join으로 로딩된 Order + enrich 데이터(Product·Variant·Seller by BIGINT id)다(D-41 입력 범위 제한).
 */
public record OrderResponse(
        String orderId,
        StatusView status,
        List<SellerGroupResponse> sellers,
        long totalPrice,
        ShippingAddressResponse shippingAddress) {

    public static OrderResponse fromOrderWithItems(
            Order order,
            Map<Long, Product> productById,
            Map<Long, ProductVariant> variantById,
            Map<Long, Seller> sellerById) {
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
                        variant != null ? variant.getPublicId() : null,
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getTotalPrice()));
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
                shippingAddress);
    }
}
