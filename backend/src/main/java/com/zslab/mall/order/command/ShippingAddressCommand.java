package com.zslab.mall.order.command;

/**
 * 주문 배송지 입력(Service 계층 Command). OrderShippingSnapshot 생성 원본이다.
 * jibun·detail·memo는 선택값(nullable·DDL 정합).
 */
public record ShippingAddressCommand(
        String recipientName,
        String recipientPhone,
        String zonecode,
        String addressRoad,
        String addressJibun,
        String addressDetail,
        String deliveryMemo) {
}
