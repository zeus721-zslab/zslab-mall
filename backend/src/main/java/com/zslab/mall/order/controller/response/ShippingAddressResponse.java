package com.zslab.mall.order.controller.response;

import com.zslab.mall.order.entity.OrderShippingSnapshot;

/**
 * 배송지 응답(GET 단건 상세·#6). OrderShippingSnapshot 스냅샷을 그대로 노출한다. jibun·detail·memo는 null 가능(§15 NON_NULL).
 */
public record ShippingAddressResponse(
        String recipientName,
        String recipientPhone,
        String zonecode,
        String addressRoad,
        String addressJibun,
        String addressDetail,
        String deliveryMemo) {

    public static ShippingAddressResponse from(OrderShippingSnapshot snapshot) {
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
