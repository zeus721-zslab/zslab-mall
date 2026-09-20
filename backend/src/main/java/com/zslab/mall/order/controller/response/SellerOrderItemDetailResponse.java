package com.zslab.mall.order.controller.response;

import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderShippingSnapshotProjection;
import com.zslab.mall.order.repository.SellerOrderItemOrderProjection;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import java.time.LocalDateTime;

/**
 * 셀러 품목 상세(Track 90-B-1). 목록 행 + 배송지 스냅샷 전체(출고 라벨용·마스킹 없음·수령인 정보만이며 구매자 계정 정보는 없다).
 * 관리자 {@code AdminOrderDetailResponse}와 별도 record(사유는 {@link SellerOrderItemSummaryResponse} 참조). claim·claimCount는 목록 행과 같다.
 */
public record SellerOrderItemDetailResponse(
        String orderItemId,
        String orderNo,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime orderedAt,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime paidAt,
        String productName,
        String optionLabel,
        int quantity,
        long unitPrice,
        long totalPrice,
        OrderItemStatus itemStatus,
        SellerOrderItemDeliveryResponse delivery,
        ShippingAddressResponse shippingAddress,
        SellerOrderItemClaimResponse claim,
        long claimCount) {

    public static SellerOrderItemDetailResponse of(OrderItem item, SellerOrderItemOrderProjection order,
            SellerOrderItemDeliveryResponse delivery, OrderShippingSnapshotProjection snapshot,
            SellerOrderItemClaimResponse claim, long claimCount) {
        return new SellerOrderItemDetailResponse(item.getPublicId(),
                order == null ? null : order.getOrderNo(),
                order == null ? null : order.getOrderedAt(),
                order == null ? null : order.getPaidAt(),
                item.getProductName(), item.getOptionLabel(), item.getQuantity(), item.getUnitPrice(), item.getTotalPrice(),
                item.getItemStatus(), delivery,
                snapshot == null ? null : new ShippingAddressResponse(snapshot.getRecipientName(), snapshot.getRecipientPhone(),
                        snapshot.getZonecode(), snapshot.getAddressRoad(), snapshot.getAddressJibun(), snapshot.getAddressDetail(),
                        snapshot.getDeliveryMemo()),
                claim, claimCount);
    }
}
