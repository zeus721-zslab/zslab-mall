package com.zslab.mall.order.controller.request;

import com.zslab.mall.order.command.ShippingAddressCommand;
import jakarta.validation.constraints.NotBlank;

/**
 * 배송지 요청(§4). 필수: recipientName·recipientPhone·zonecode·addressRoad. jibun·detail·memo는 선택(nullable·DDL 정합).
 */
public record ShippingAddressRequest(
        @NotBlank String recipientName,
        @NotBlank String recipientPhone,
        @NotBlank String zonecode,
        @NotBlank String addressRoad,
        String addressJibun,
        String addressDetail,
        String deliveryMemo) {

    public ShippingAddressCommand toCommand() {
        return new ShippingAddressCommand(
                recipientName, recipientPhone, zonecode, addressRoad, addressJibun, addressDetail, deliveryMemo);
    }
}
