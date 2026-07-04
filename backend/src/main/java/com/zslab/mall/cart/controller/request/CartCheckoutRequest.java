package com.zslab.mall.cart.controller.request;

import com.zslab.mall.order.controller.request.ShippingAddressRequest;
import com.zslab.mall.payment.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 장바구니 결제 요청(Track 41 β). 주문 대상은 로그인 buyer의 장바구니 selected=true 품목이므로 items를 받지 않고
 * (서버가 조회) 배송지·결제수단만 수령한다. shippingAddress는 직접주문과 동일한 {@link ShippingAddressRequest}를 재사용한다.
 *
 * @param shippingAddress 배송지(직접주문 DTO 재사용)
 * @param method          첫 결제 수단
 */
public record CartCheckoutRequest(
        @NotNull @Valid ShippingAddressRequest shippingAddress,
        @NotNull PaymentMethod method) {
}
