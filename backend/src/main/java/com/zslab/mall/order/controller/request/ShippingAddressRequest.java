package com.zslab.mall.order.controller.request;

import com.zslab.mall.order.command.ShippingAddressCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 배송지 요청(§4). 필수: recipientName·recipientPhone·zonecode·addressRoad. jibun·detail·memo는 선택(nullable·DDL 정합).
 *
 * <p>@Size 상한은 order_shipping_snapshot DDL 컬럼 길이와 동일 SoT. deliveryMemo는 TEXT라 정책 상한 500자(D-268).
 */
public record ShippingAddressRequest(
        @NotBlank @Size(max = 50) String recipientName, // SoT: recipient_name VARCHAR(50)
        @NotBlank @Size(max = 20) String recipientPhone, // SoT: recipient_phone VARCHAR(20)
        @NotBlank @Size(max = 10) String zonecode, // SoT: zonecode VARCHAR(10)
        @NotBlank @Size(max = 200) String addressRoad, // SoT: address_road VARCHAR(200)
        @Size(max = 200) String addressJibun, // SoT: address_jibun VARCHAR(200)
        @Size(max = 200) String addressDetail, // SoT: address_detail VARCHAR(200)
        @Size(max = 500) String deliveryMemo) { // SoT: delivery_memo TEXT · 정책 상한 500

    public ShippingAddressCommand toCommand() {
        return new ShippingAddressCommand(
                recipientName, recipientPhone, zonecode, addressRoad, addressJibun, addressDetail, deliveryMemo);
    }
}
