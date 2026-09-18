package com.zslab.mall.delivery.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryDirection;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.order.controller.response.ShippingAddressResponse;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.repository.OrderItemOrderProjection;
import com.zslab.mall.order.repository.OrderShippingSnapshotProjection;
import java.time.LocalDateTime;

/**
 * 관리자 배송 상세(Track 89-B D-184). 목록 행 + 배송지 스냅샷(관리자 주문 상세와 같이 마스킹 없음) + 연결 품목·클레임 정보.
 *
 * @param orderItemId     주문 품목 public_id(oit_)
 * @param orderItemStatus 품목 현재 상태
 * @param shippingAddress 주문 배송지 스냅샷(부재 시 null)
 * @param claimStatus     연계 클레임 상태·원 발송은 null
 */
public record AdminDeliveryDetailResponse(
        String deliveryId,
        String orderId,
        String orderNo,
        String orderItemId,
        String productName,
        String optionLabel,
        int quantity,
        OrderItemStatus orderItemStatus,
        DeliveryDirection direction,
        DeliveryStatus status,
        DeliveryCarrier carrier,
        String trackingNo,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime shippedAt,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime deliveredAt,
        ShippingAddressResponse shippingAddress,
        String claimId,
        ClaimType claimType,
        ClaimStatus claimStatus) {

    public static AdminDeliveryDetailResponse from(Delivery delivery, OrderItem item, OrderItemOrderProjection order,
            OrderShippingSnapshotProjection snapshot, Claim claim) {
        return new AdminDeliveryDetailResponse(
                delivery.getPublicId(),
                order == null ? null : order.getOrderPublicId(),
                order == null ? null : order.getOrderNo(),
                item == null ? null : item.getPublicId(),
                item == null ? null : item.getProductName(),
                item == null ? null : item.getOptionLabel(),
                item == null ? 0 : item.getQuantity(),
                item == null ? null : item.getItemStatus(),
                delivery.getDirection(),
                delivery.getStatus(),
                delivery.getCarrier(),
                delivery.getTrackingNo(),
                delivery.getShippedAt(),
                delivery.getDeliveredAt(),
                snapshot == null ? null : toShippingAddress(snapshot),
                claim == null ? null : claim.getPublicId(),
                claim == null ? null : claim.getType(),
                claim == null ? null : claim.getStatus());
    }

    private static ShippingAddressResponse toShippingAddress(OrderShippingSnapshotProjection snapshot) {
        return new ShippingAddressResponse(
                snapshot.getRecipientName(),
                snapshot.getRecipientPhone(),
                snapshot.getZonecode(),
                snapshot.getAddressRoad(),
                snapshot.getAddressJibun(),
                snapshot.getAddressDetail(),
                snapshot.getDeliveryMemo());
    }
}
